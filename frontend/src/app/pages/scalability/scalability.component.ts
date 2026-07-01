import { Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor, BenchmarkSummaryView, ResultIdResponse } from '../../app.models';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([LineChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

interface ScalabilityRow {
  size: number;
  sourceRows: number;
  indexingMs: number;
  learningMs: number;
  joinMs: number;
  fuzzyMs: number;
  totalMs: number;
  precision: number;
  recall: number;
}

@Component({
  selector: 'app-scalability',
  standalone: true,
  imports: [RouterLink, DecimalPipe, NgFor, NgIf],
  templateUrl: './scalability.component.html',
  styleUrl: './scalability.component.scss'
})
export class ScalabilityComponent implements OnInit, OnDestroy {
  dblpBenchmarks: BenchmarkDescriptor[] = [];
  rows: ScalabilityRow[] = [];
  running = false;
  statusMessage = '';
  timingChart?: echarts.ECharts;

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

  runAll(): void {
    const pairIds = this.dblpBenchmarks.map(b => b.pairId);
    if (pairIds.length === 0) return;
    this.running = true;
    this.statusMessage = `Running ${pairIds.length} DBLP benchmarks...`;
    this.benchmarkService.runBatch(pairIds).subscribe({
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

  private loadResults(): void {
    this.benchmarkService.listResults().subscribe(results => {
      const dblpResults = results.filter(r => r.pairId.startsWith('dblp-'));
      this.rows = dblpResults
        .map(r => ({
          size: this.parseSize(r.pairId),
          sourceRows: 0,
          indexingMs: r.indexingTimeMs,
          learningMs: r.learningTimeMs,
          joinMs: r.joinTimeMs,
          fuzzyMs: r.fuzzyTimeMs,
          totalMs: r.durationMs,
          precision: r.precision,
          recall: r.recall
        }))
        .sort((a, b) => a.size - b.size);

      for (const b of this.dblpBenchmarks) {
        const row = this.rows.find(r => r.size === this.parseSize(b.pairId));
        if (row) row.sourceRows = b.sourceRows;
      }

      this.updateChart();
    });
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
    const sizes = this.rows.map(r => r.sourceRows);
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
            s += `${p.marker} ${p.seriesName}: ${p.value}ms<br/>`;
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
        type: 'log',
        name: 'Number of rows',
        nameLocation: 'center',
        nameGap: 35,
        nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
        axisLabel: { fontFamily: 'Space Grotesk' },
        min: 10,
        max: Math.max(...sizes, 100) * 2
      },
      yAxis: {
        type: 'log',
        name: 'Running time (ms)',
        nameLocation: 'center',
        nameGap: 50,
        nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
        axisLabel: { fontFamily: 'Space Grotesk' },
        min: 1,
        max: Math.max(...this.rows.map(r => r.totalMs), 10) * 2
      },
      series: [
        {
          name: 'Total',
          type: 'line',
          data: this.rows.map(r => r.totalMs),
          smooth: true,
          symbol: 'circle',
          lineStyle: { width: 2, color: '#c1121f' },
          itemStyle: { color: '#c1121f' }
        },
        {
          name: 'Indexing',
          type: 'line',
          data: this.rows.map(r => r.indexingMs),
          smooth: true,
          symbol: 'diamond',
          lineStyle: { width: 2, color: '#0f4c5c' },
          itemStyle: { color: '#0f4c5c' }
        },
        {
          name: 'Learning',
          type: 'line',
          data: this.rows.map(r => r.learningMs),
          smooth: true,
          symbol: 'triangle',
          lineStyle: { width: 2, color: '#e36414' },
          itemStyle: { color: '#e36414' }
        },
        {
          name: 'Join',
          type: 'line',
          data: this.rows.map(r => r.joinMs),
          smooth: true,
          symbol: 'rect',
          lineStyle: { width: 2, color: '#5fad56' },
          itemStyle: { color: '#5fad56' }
        }
      ]
    };
  }
}