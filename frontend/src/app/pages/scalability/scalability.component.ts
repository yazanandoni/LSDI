import { Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService, SystemInfo } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, MarkLineComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, MarkLineComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

interface ScalabilityRow {
  size: number;
  sourceRows: number;
  method: string;
  totalMs: number;
  indexingMs: number;
  learningMs: number;
  joinMs: number;
  fuzzyMs: number;
  precision: number;
  recall: number;
  timedOut: boolean;
}

@Component({
  selector: 'app-scalability',
  standalone: true,
  imports: [RouterLink, DecimalPipe, NgFor, NgIf, FormsModule],
  templateUrl: './scalability.component.html',
  styleUrl: './scalability.component.scss'
})
export class ScalabilityComponent implements OnInit, OnDestroy {
  dblpBenchmarks: BenchmarkDescriptor[] = [];
  rows: ScalabilityRow[] = [];
  running = false;
  statusMessage = '';
  timingChart?: echarts.ECharts;
  selectedMethod = 'AJ';
  methods = ['AJ', 'SM', 'FJ-C', 'FJ-O', 'Compare all'];
  systemInfo?: SystemInfo;

  constructor(private benchmarkService: BenchmarkService) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe(benchmarks => {
      this.dblpBenchmarks = benchmarks.filter(b => b.pairId.startsWith('dblp-'));
      this.dblpBenchmarks.sort((a, b) => a.sourceRows - b.sourceRows);
    });
    this.benchmarkService.getSystemInfo().subscribe((info) => (this.systemInfo = info));
    this.loadResults();
  }

  get heapGb(): string {
    return this.systemInfo
      ? (this.systemInfo.maxHeapBytes / (1024 * 1024 * 1024)).toFixed(1)
      : '?';
  }

  ngOnDestroy(): void {
    this.timingChart?.dispose();
  }

  get hasResults(): boolean {
    return this.rows.length > 0;
  }

  runSelected(): void {
    const pairIds = this.dblpBenchmarks.map(b => b.pairId); // sorted small -> large
    if (pairIds.length === 0) return;
    const methods = this.selectedMethod === 'Compare all'
      ? ['AJ', 'SM', 'FJ-C', 'FJ-O'] : [this.selectedMethod];

    // Strictly sequential via the async job API (start + poll): parallel runs
    // compete for CPU and distort the timing curves, and holding one HTTP
    // connection per run dies at the browser's connection limit.
    const runs: { pairId: string; method: string }[] = [];
    for (const m of methods) {
      for (const id of pairIds) runs.push({ pairId: id, method: m });
    }

    this.running = true;
    this.benchmarkService.runQueue(runs, (msg) => (this.statusMessage = msg)).then((failures) => {
      this.running = false;
      this.statusMessage = failures.length > 0
        ? `Done with errors: ${failures.join('; ')}` : 'All runs complete.';
      this.loadResults();
    });
  }

  private loadResults(): void {
    this.benchmarkService.listResults().subscribe(results => {
      const dblpResults = results.filter(r => r.pairId.startsWith('dblp-'));
      this.rows = dblpResults
        .map(r => {
          const size = this.parseSize(r.pairId);
          const bm = this.dblpBenchmarks.find(b => this.parseSize(b.pairId) === size);
          return {
            size,
            sourceRows: bm ? bm.sourceRows : 0,
            method: r.method || 'AJ',
            totalMs: r.durationMs,
            indexingMs: r.indexingTimeMs || 0,
            learningMs: r.learningTimeMs || 0,
            joinMs: r.joinTimeMs || 0,
            fuzzyMs: r.fuzzyTimeMs || 0,
            precision: r.precision,
            recall: r.recall,
            timedOut: r.timedOut
          };
        })
        .sort((a, b) => a.size - b.size || a.method.localeCompare(b.method));

      this.updateChart();
    });
  }

  get sizes(): number[] {
    return [...new Set(this.rows.map(r => r.size))].sort((a, b) => a - b);
  }

  get activeMethods(): string[] {
    return [...new Set(this.rows.map(r => r.method))].sort();
  }

  rowsFor(size: number, method: string): ScalabilityRow | undefined {
    return this.rows.find(r => r.size === size && r.method === method);
  }

  private parseSize(pairId: string): number {
    return parseInt(pairId.replace('dblp-', ''), 10) || 0;
  }

  private updateChart(): void {
    if (this.rows.length === 0) return;
    setTimeout(() => {
      this.timingChart?.dispose();
      const el = document.getElementById('timingChart');
      if (!el) return;
      this.timingChart = echarts.init(el);
      this.timingChart.setOption(this.buildChartOption());
    });
  }

  downloadChart(): void {
    if (!this.timingChart) return;
    const a = document.createElement('a');
    a.href = this.timingChart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' });
    a.download = 'figure8-dblp-running-time.png';
    a.click();
  }

  /** Smallest "nice" value (1/2/5 x 10^k) at or above v. */
  private static niceCeil(v: number): number {
    if (v <= 0) return 1;
    const base = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 2, 5, 10]) {
      if (v <= m * base) return m * base;
    }
    return 10 * base;
  }

  private static formatRows(n: number): string {
    if (n >= 1_000_000) return (n / 1_000_000) + 'M';
    if (n >= 1000) return (n / 1000) + 'K';
    return String(n);
  }

  private static formatSec(v: number): string {
    return v < 1 ? v.toFixed(2) : v < 10 ? v.toFixed(1) : String(Math.round(v));
  }

  /**
   * Paper Figure 8: AJ drawn as stacked bars broken into its stages
   * (Indexing / Find Trans. / Equi-Join), the baselines as lines, on a BROKEN
   * y-axis — a lower panel scaled for AJ's near-constant seconds and an upper
   * panel for the baselines' hundreds of seconds. Baseline lines are drawn in
   * both panels and clipped, so they visually "shoot through" the break.
   * Timed-out runs are pinned at the cut-off with an explicit marker.
   */
  private buildChartOption(): any {
    const font = { fontFamily: 'Space Grotesk', fontSize: 12 };
    const sizes = this.sizes;
    const labels = sizes.map(s => {
      const r = this.rows.find(r => r.size === s);
      return ScalabilityComponent.formatRows(r && r.sourceRows ? r.sourceRows : s);
    });
    const colors: Record<string, string> = {
      'SM': '#c1121f', 'FJ-C': '#5fad56', 'FJ-O': '#e36414'
    };
    const symbols: Record<string, string> = {
      'SM': 'diamond', 'FJ-C': 'triangle', 'FJ-O': 'rect'
    };
    const sec = (ms: number) => ms / 1000;

    const ajRows = this.rows.filter(r => r.method === 'AJ');
    const baselineMethods = this.activeMethods.filter(m => m !== 'AJ');
    const maxSec = Math.max(...this.rows.map(r => sec(r.totalMs)), 0);
    const ajMaxSec = ajRows.length > 0 ? Math.max(...ajRows.map(r => sec(r.totalMs))) : maxSec;
    const lowerMax = ScalabilityComponent.niceCeil(ajMaxSec * 1.4 || 1);
    const split = maxSec > lowerMax; // need the broken axis?
    const upperMax = ScalabilityComponent.niceCeil(maxSec * 1.08);
    const lowerGrid = split ? 1 : 0;

    // AJ stacked bars (paper stages; our §5 fuzzy time folds into Equi-Join).
    const stageDefs: { name: string; color: string; val: (r: ScalabilityRow) => number }[] = [
      { name: 'Indexing', color: '#0f4c5c', val: r => sec(r.indexingMs) },
      { name: 'Find Trans.', color: '#3a7d8c', val: r => sec(r.learningMs) },
      { name: 'Equi-Join', color: '#8fb8c4', val: r => sec(r.joinMs + r.fuzzyMs) }
    ];
    const series: any[] = ajRows.length === 0 ? [] : stageDefs.map(st => ({
      name: st.name,
      type: 'bar',
      stack: 'AJ',
      xAxisIndex: lowerGrid,
      yAxisIndex: lowerGrid,
      barWidth: '35%',
      itemStyle: { color: st.color },
      data: sizes.map(s => {
        const r = this.rowsFor(s, 'AJ');
        return r ? st.val(r) : null;
      })
    }));

    // Baseline lines — one copy per panel; each panel clips what is out of range.
    const lineSeries = (m: string, gridIdx: number) => ({
      name: m,
      type: 'line',
      xAxisIndex: gridIdx,
      yAxisIndex: gridIdx,
      data: sizes.map(s => {
        const r = this.rowsFor(s, m);
        if (!r) return null;
        const point: any = { value: sec(r.totalMs), timedOut: r.timedOut };
        if (r.timedOut) {
          point.symbol = 'pin';
          point.symbolSize = 24;
          point.label = {
            show: true, formatter: 'timeout', position: 'top',
            color: '#b3541e', fontFamily: 'Space Grotesk', fontSize: 10
          };
        }
        return point;
      }),
      symbol: symbols[m] || 'circle',
      symbolSize: 8,
      lineStyle: { width: 2, color: colors[m] || '#999' },
      itemStyle: { color: colors[m] || '#999' },
      connectNulls: false,
      clip: true
    });
    for (const m of baselineMethods) {
      series.push(lineSeries(m, 0));
      if (split) series.push(lineSeries(m, 1));
    }

    // Dashed cut-off line at the time budget, in the panel that contains it.
    const timedOutSec = this.rows.filter(r => r.timedOut).map(r => sec(r.totalMs));
    if (timedOutSec.length > 0 && series.length > 0) {
      const budget = Math.min(...timedOutSec);
      const target = series.find(s => s.yAxisIndex === (split && budget > lowerMax ? 0 : lowerGrid)) || series[0];
      target.markLine = {
        silent: true,
        symbol: 'none',
        lineStyle: { type: 'dashed', color: '#b3541e' },
        label: { formatter: 'timeout', position: 'insideEndTop', color: '#b3541e', fontFamily: 'Space Grotesk' },
        data: [{ yAxis: budget }]
      };
    }

    const xAxisBase = {
      type: 'category',
      data: labels,
      axisLabel: { fontFamily: 'Space Grotesk' }
    };
    const xAxisNamed = {
      ...xAxisBase,
      name: 'Number of rows',
      nameLocation: 'center',
      nameGap: 32,
      nameTextStyle: font
    };
    const yAxisBase = {
      type: 'value',
      axisLabel: { fontFamily: 'Space Grotesk' },
      nameTextStyle: font
    };

    return {
      title: {
        text: 'DBLP',
        left: 'center',
        top: 8,
        textStyle: { fontFamily: 'Space Grotesk', fontSize: 14 }
      },
      tooltip: {
        trigger: 'item',
        formatter: (p: any) => {
          const v = ScalabilityComponent.formatSec(Array.isArray(p.value) ? p.value[1] : p.value);
          if (p.seriesType === 'bar') {
            return `<b>${p.name} rows</b><br/>${p.marker} AJ ${p.seriesName}: ${v}s`;
          }
          const cut = p.data && p.data.timedOut ? ' — TIMEOUT (cut off)' : '';
          return `<b>${p.name} rows</b><br/>${p.marker} ${p.seriesName}: ${v}s${cut}`;
        }
      },
      legend: {
        bottom: 0,
        textStyle: { fontFamily: 'Space Grotesk', fontSize: 12 }
      },
      grid: split
        ? [
            { left: 70, right: 30, top: 48, height: '30%' },
            { left: 70, right: 30, bottom: 92, height: '32%' }
          ]
        : [{ left: 70, right: 30, top: 60, bottom: 92 }],
      xAxis: split
        ? [
            { ...xAxisBase, gridIndex: 0, axisLabel: { show: false }, axisTick: { show: false } },
            { ...xAxisNamed, gridIndex: 1 }
          ]
        : [{ ...xAxisNamed, gridIndex: 0 }],
      yAxis: split
        ? [
            { ...yAxisBase, gridIndex: 0, min: lowerMax, max: upperMax },
            { ...yAxisBase, gridIndex: 1, min: 0, max: lowerMax, name: 'Time (sec)', nameLocation: 'center', nameGap: 45 }
          ]
        : [{ ...yAxisBase, gridIndex: 0, min: 0, max: upperMax, name: 'Time (sec)', nameLocation: 'center', nameGap: 45 }],
      series
    };
  }
}