import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkSummaryView } from '../../app.models';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf, DecimalPipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  recentResults: BenchmarkSummaryView[] = [];
  running = false;
  summaryStats = {
    totalRuns: 0,
    avgPrecision: 0,
    avgRecall: 0
  };

  constructor(
    private benchmarkService: BenchmarkService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.benchmarkService.listResults().subscribe((results) => {
      this.recentResults = results.slice(0, 5);
      if (results.length > 0) {
        const precisionSum = results.reduce((sum, r) => sum + r.precision, 0);
        const recallSum = results.reduce((sum, r) => sum + r.recall, 0);
        this.summaryStats = {
          totalRuns: results.length,
          avgPrecision: precisionSum / results.length,
          avgRecall: recallSum / results.length
        };
      }
    });
  }

  runAll(): void {
    this.running = true;
    this.benchmarkService.runAllBenchmarks().subscribe({
      next: () => {
        this.running = false;
        this.router.navigate(['/results']);
      },
      error: () => {
        this.running = false;
      }
    });
  }
}