import { Component, OnDestroy, OnInit } from '@angular/core';
import { DecimalPipe, NgFor, NgIf, SlicePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkSummaryView } from '../../app.models';
import { buildMatchBreakdownOption, buildPrecisionRecallOption } from './results-charts';
import * as echarts from 'echarts/core';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [RouterLink, DecimalPipe, NgFor, NgIf, SlicePipe],
  templateUrl: './results.component.html',
  styleUrl: './results.component.scss'
})
export class ResultsComponent implements OnInit, OnDestroy {
  results: BenchmarkSummaryView[] = [];
  selectedResult: BenchmarkSummaryView | null = null;
  precisionRecallChart?: echarts.ECharts;
  matchBreakdownChart?: echarts.ECharts;

  constructor(private benchmarkService: BenchmarkService) {}

  ngOnInit(): void {
    this.benchmarkService.listResults().subscribe((results) => {
      this.results = results;
      this.selectedResult = results[0] ?? null;
      this.updateCharts();
    });
  }

  ngOnDestroy(): void {
    this.precisionRecallChart?.dispose();
    this.matchBreakdownChart?.dispose();
  }

  selectResult(result: BenchmarkSummaryView): void {
    this.selectedResult = result;
    this.updateCharts();
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
