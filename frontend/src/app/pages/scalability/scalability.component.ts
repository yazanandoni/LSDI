import { Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([LineChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

interface ScalabilityRow {
  size: number;
  sourceRows: number;
  method: string;
  totalMs: number;
  precision: number;
  recall: number;
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

  constructor(private benchmarkService: BenchmarkService) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe(benchmarks => {
      this.dblpBenchmarks = benchmarks.filter(b => b.pairId.startsWith('dblp-'));
      this.dblpBenchmarks.sort((a, b) => a.sourceRows - b.sourceRows);
    });
    this.loadResults();
  }

  ngOnDestroy(): void {
    this.timingChart?.dispose();
  }

  get hasResults(): boolean {
    return this.rows.length > 0;
  }

  runSelected(): void {
    const pairIds = this.dblpBenchmarks.map(b => b.pairId);
    if (pairIds.length === 0) return;

    this.running = true;
    const isCompare = this.selectedMethod === 'Compare all';
    const methods = isCompare ? ['AJ', 'SM', 'FJ-C', 'FJ-O'] : [this.selectedMethod];
    this.statusMessage = `Running ${pairIds.length} DBLP size(s) with ${isCompare ? 'all 4 methods' : this.selectedMethod}...`;

    const mkMethods = (m: string) => pairIds.map(() => m);

    if (isCompare) {
      let completed = 0;
      for (const m of methods) {
        this.benchmarkService.runBatch(pairIds, mkMethods(m)).subscribe({
          next: () => {
            completed++;
            if (completed === methods.length) {
              this.statusMessage = 'All runs complete.';
              this.running = false;
              this.loadResults();
            }
          },
          error: (err) => {
            this.statusMessage = 'Error: ' + err.message;
            this.running = false;
          }
        });
      }
    } else {
      this.benchmarkService.runBatch(pairIds, mkMethods(this.selectedMethod)).subscribe({
        next: () => {
          this.statusMessage = 'All runs complete.';
          this.running = false;
          this.loadResults();
        },
        error: (err) => {
          this.statusMessage = 'Error: ' + err.message;
          this.running = false;
        }
      });
    }
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
            precision: r.precision,
            recall: r.recall
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

  private buildChartOption(): any {
    const sizes = this.sizes;
    const rowLabels = sizes.map(s => {
      const r = this.rows.find(r => r.size === s);
      return r ? String(r.sourceRows) : String(s);
    });
    const colors: Record<string, string> = {
      'AJ': '#0f4c5c', 'SM': '#c1121f', 'FJ-C': '#5fad56', 'FJ-O': '#e36414'
    };
    const symbols: Record<string, string> = {
      'AJ': 'circle', 'SM': 'diamond', 'FJ-C': 'triangle', 'FJ-O': 'rect'
    };
    const series = this.activeMethods.map(m => ({
      name: m,
      type: 'line',
      data: sizes.map(s => {
        const r = this.rows.find(x => x.size === s && x.method === m);
        return r ? r.totalMs : null;
      }),
      smooth: false,
      symbol: symbols[m] || 'circle',
      lineStyle: { width: 2, color: colors[m] || '#999' },
      itemStyle: { color: colors[m] || '#999' },
      connectNulls: false
    }));

    return {
      title: {
        text: 'Figure 8 — Running Time vs Table Size',
        left: 'center',
        top: 10,
        textStyle: { fontFamily: 'Space Grotesk', fontSize: 14 }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any[]) => {
          let s = `<b>${params[0].axisValue} rows</b><br/>`;
          for (const p of params) {
            if (p.value != null) s += `${p.marker} ${p.seriesName}: ${p.value}ms<br/>`;
          }
          return s;
        }
      },
      legend: {
        bottom: 0,
        textStyle: { fontFamily: 'Space Grotesk', fontSize: 12 }
      },
      grid: { left: 70, right: 30, bottom: 60, top: 60 },
      xAxis: {
        type: 'category',
        name: 'Number of rows',
        nameLocation: 'center',
        nameGap: 35,
        nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
        axisLabel: { fontFamily: 'Space Grotesk' },
        data: rowLabels
      },
      yAxis: {
        type: 'log',
        name: 'Running time (ms)',
        nameLocation: 'center',
        nameGap: 50,
        nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
        axisLabel: { fontFamily: 'Space Grotesk' },
        min: 1
      },
      series
    };
  }
}