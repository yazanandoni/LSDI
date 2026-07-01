import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

@Component({
  selector: 'app-benchmarks',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf, FormsModule],
  templateUrl: './benchmarks.component.html',
  styleUrl: './benchmarks.component.scss'
})
export class BenchmarksComponent implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  methods = ['AJ', 'SM', 'FJ-C', 'FJ-O'];
  webSelectedIds = new Set<string>();
  dblpSelectedIds = new Set<string>();
  methodMap = new Map<string, string>();
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

  get webBenchmarks(): BenchmarkDescriptor[] {
    return this.benchmarks.filter(b => !b.pairId.startsWith('dblp-'));
  }

  get dblpBenchmarks(): BenchmarkDescriptor[] {
    return this.benchmarks.filter(b => b.pairId.startsWith('dblp-'));
  }

  getMethod(pairId: string): string {
    return this.methodMap.get(pairId) || 'AJ';
  }

  setMethod(pairId: string, method: string): void {
    this.methodMap.set(pairId, method);
  }

  toggleWebSelection(pairId: string): void {
    if (this.webSelectedIds.has(pairId)) {
      this.webSelectedIds.delete(pairId);
    } else {
      this.webSelectedIds.add(pairId);
    }
  }

  toggleDblpSelection(pairId: string): void {
    if (this.dblpSelectedIds.has(pairId)) {
      this.dblpSelectedIds.delete(pairId);
    } else {
      this.dblpSelectedIds.add(pairId);
    }
  }

  runWebSelected(): void {
    this.runSelected(this.webSelectedIds);
  }

  runDblpSelected(): void {
    this.runSelected(this.dblpSelectedIds);
  }

  private runSelected(selectedIds: Set<string>): void {
    const ids = Array.from(selectedIds);
    if (ids.length === 0) return;
    const methods = ids.map(id => this.methodMap.get(id) || 'AJ');
    this.running = true;
    this.statusMessage = `Running ${ids.length} benchmark(s)...`;
    this.benchmarkService.runBatch(ids, methods).subscribe({
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

  runSingle(pairId: string, method: string): void {
    this.running = true;
    this.statusMessage = `Running ${pairId} (${method})...`;
    this.benchmarkService.runBenchmark(pairId, method).subscribe({
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

  webSelectAll(): void {
    this.webBenchmarks.forEach(b => this.webSelectedIds.add(b.pairId));
  }

  webDeselectAll(): void {
    this.webSelectedIds.clear();
  }

  dblpSelectAll(): void {
    this.dblpBenchmarks.forEach(b => this.dblpSelectedIds.add(b.pairId));
  }

  dblpDeselectAll(): void {
    this.dblpSelectedIds.clear();
  }
}