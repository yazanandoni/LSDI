import { Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, NgFor, NgIf, SlicePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkSummary } from '../../app.models';
import { API_BASE_URL } from '../../core/api.config';
import {
  MethodAverage,
  buildMatchBreakdownOption,
  buildMethodAverageOption,
  buildPrecisionRecallOption
} from './results-charts';
import * as echarts from 'echarts/core';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [RouterLink, DecimalPipe, NgFor, NgIf, SlicePipe],
  templateUrl: './results.component.html',
  styleUrl: './results.component.scss'
})
export class ResultsComponent implements OnInit, OnDestroy {
  readonly apiBase = API_BASE_URL;
  readonly Math = Math;
  results: BenchmarkSummary[] = [];
  selectedResult: BenchmarkSummary | null = null;
  methodAverages: MethodAverage[] = [];
  syntheticAverages: MethodAverage[] = [];
  precisionRecallChart?: echarts.ECharts;
  matchBreakdownChart?: echarts.ECharts;
  methodAverageChart?: echarts.ECharts;
  syntheticAverageChart?: echarts.ECharts;

  constructor(
    private benchmarkService: BenchmarkService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadResults();
  }

  ngOnDestroy(): void {
    this.precisionRecallChart?.dispose();
    this.matchBreakdownChart?.dispose();
    this.methodAverageChart?.dispose();
    this.syntheticAverageChart?.dispose();
  }

  private loadResults(): void {
    this.benchmarkService.listResults().subscribe((results) => {
      this.results = results;
      this.selectedResult = results[0] ?? null;
      this.methodAverages = this.computeMethodAverages(
        results, (id) => !id.startsWith('dblp-') && !id.startsWith('synthetic-'));
      this.syntheticAverages = this.computeMethodAverages(
        results, (id) => id.startsWith('synthetic-'));
      this.updateCharts();
      this.updateMethodAverageChart();
      this.syncHeights();
    });
  }

  clearAll(): void {
    if (!confirm(`Delete all ${this.results.length} stored results? This cannot be undone.`)) return;
    this.benchmarkService.clearResults().subscribe(() => {
      this.results = [];
      this.selectedResult = null;
      this.methodAverages = [];
      this.syntheticAverages = [];
      this.precisionRecallChart?.dispose();
      this.matchBreakdownChart?.dispose();
      this.methodAverageChart?.dispose();
      this.syntheticAverageChart?.dispose();
    });
  }

  selectResult(result: BenchmarkSummary): void {
    this.selectedResult = result;
    this.updateCharts();
    this.syncHeights();
  }

  viewTrace(result: BenchmarkSummary): void {
    this.router.navigate(['/trace', result.resultId]);
  }

  downloadFig5(): void {
    if (!this.methodAverageChart) return;
    const a = document.createElement('a');
    a.href = this.methodAverageChart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' });
    a.download = 'figure5-method-averages.png';
    a.click();
  }

  downloadFig5c(): void {
    if (!this.syntheticAverageChart) return;
    const a = document.createElement('a');
    a.href = this.syntheticAverageChart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' });
    a.download = 'figure5c-synthetic-averages.png';
    a.click();
  }

  /**
   * Figure 5b / Figure 5c aggregation over the runs selected by `include`,
   * using the paper's rule (sec. 6.1): keep the LATEST run per (pair, method);
   * average precision over non-empty runs only, recall over all runs.
   * Timed-out runs join nothing, so they count as recall 0.
   */
  private computeMethodAverages(results: BenchmarkSummary[],
                                include: (pairId: string) => boolean): MethodAverage[] {
    const latest = new Map<string, BenchmarkSummary>();
    for (const r of results) { // results are newest-first, keep first occurrence
      if (!include(r.pairId)) continue;
      const key = `${r.pairId}|${r.method || 'AJ'}`;
      if (!latest.has(key)) latest.set(key, r);
    }
    const byMethod = new Map<string, BenchmarkSummary[]>();
    for (const r of latest.values()) {
      const m = r.method || 'AJ';
      byMethod.set(m, [...(byMethod.get(m) || []), r]);
    }
    const averages: MethodAverage[] = [];
    for (const [method, runs] of byMethod) {
      const withGt = runs.filter(r => r.groundTruthPairs > 0);
      if (withGt.length === 0) continue;
      const nonEmpty = withGt.filter(r => r.joinedPairs > 0);
      const precision = nonEmpty.length === 0 ? 0
        : nonEmpty.reduce((s, r) => s + r.precision, 0) / nonEmpty.length;
      const recall = withGt.reduce((s, r) => s + r.recall, 0) / withGt.length;
      averages.push({ method, precision, recall, cases: withGt.length });
    }
    return averages.sort((a, b) => a.method.localeCompare(b.method));
  }

  private updateMethodAverageChart(): void {
    setTimeout(() => {
      this.methodAverageChart?.dispose();
      const el = document.getElementById('methodAvgChart');
      if (el && this.methodAverages.length > 0) {
        this.methodAverageChart = echarts.init(el);
        this.methodAverageChart.setOption(buildMethodAverageOption(this.methodAverages));
      }
      this.syntheticAverageChart?.dispose();
      const synthEl = document.getElementById('synthAvgChart');
      if (synthEl && this.syntheticAverages.length > 0) {
        this.syntheticAverageChart = echarts.init(synthEl);
        this.syntheticAverageChart.setOption(buildMethodAverageOption(
          this.syntheticAverages, 'Synthetic'));
      }
    });
  }

  private syncHeights(): void {
    setTimeout(() => {
      const detail = document.querySelector('.results-detail');
      const list = document.querySelector('.results-list');
      if (detail && list) {
        const h = detail.getBoundingClientRect().height;
        (list as HTMLElement).style.maxHeight = h + 'px';
      }
    });
  }

  private updateCharts(): void {
    if (!this.selectedResult) return;

    setTimeout(() => {
      const r = this.selectedResult!;
      const precision = r.precision ?? 0;
      const recall = r.recall ?? 0;
      const mismatches = r.mismatches.length;
      const tp = r.truePositives;

      this.precisionRecallChart?.dispose();
      this.matchBreakdownChart?.dispose();

      const precisionEl = document.getElementById('precisionChart');
      const breakdownEl = document.getElementById('breakdownChart');

      if (precisionEl) {
        this.precisionRecallChart = echarts.init(precisionEl);
        this.precisionRecallChart.setOption(buildPrecisionRecallOption(precision, recall));
      }
      if (breakdownEl) {
        this.matchBreakdownChart = echarts.init(breakdownEl);
        this.matchBreakdownChart.setOption(buildMatchBreakdownOption(tp, mismatches));
      }
    });
  }
}
