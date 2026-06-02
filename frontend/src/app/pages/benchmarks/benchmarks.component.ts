import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

@Component({
  selector: 'app-benchmarks',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf],
  templateUrl: './benchmarks.component.html',
  styleUrl: './benchmarks.component.scss'
})
export class BenchmarksComponent implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  selectedIds = new Set<string>();
  running = false;
  statusMessage = '';

  constructor(
    private benchmarkService: BenchmarkService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe((benchmarks) => {
      this.benchmarks = benchmarks;
    });
  }

  toggleSelection(pairId: string): void {
    if (this.selectedIds.has(pairId)) {
      this.selectedIds.delete(pairId);
    } else {
      this.selectedIds.add(pairId);
    }
  }

  runSelected(): void {
    const ids = Array.from(this.selectedIds);
    if (ids.length === 0) return;
    this.running = true;
    this.statusMessage = `Running ${ids.length} benchmark(s)...`;
    this.benchmarkService.runBatch(ids).subscribe({
      next: () => {
        this.running = false;
        this.router.navigate(['/results']);
      },
      error: (err) => {
        this.running = false;
        this.statusMessage = 'Error: ' + (err.message || 'failed to run');
      }
    });
  }

  runSingle(pairId: string): void {
    this.running = true;
    this.statusMessage = `Running ${pairId}...`;
    this.benchmarkService.runBenchmark(pairId).subscribe({
      next: () => {
        this.running = false;
        this.router.navigate(['/results']);
      },
      error: (err) => {
        this.running = false;
        this.statusMessage = 'Error: ' + (err.message || 'failed to run');
      }
    });
  }

  selectAll(): void {
    this.benchmarks.forEach(b => this.selectedIds.add(b.pairId));
  }

  deselectAll(): void {
    this.selectedIds.clear();
  }
}