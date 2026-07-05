import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

@Component({
  selector: 'app-dblp',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf, FormsModule],
  templateUrl: './dblp.component.html',
  styleUrl: './dblp.component.scss'
})
export class DblpComponent implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  methods = ['AJ', 'SM', 'FJ-C', 'FJ-O', 'All'];
  allMethods = ['AJ', 'SM', 'FJ-C', 'FJ-O'];
  selectedIds = new Set<string>();
  methodMap = new Map<string, string>();
  running = false;
  statusMessage = '';

  constructor(
    private benchmarkService: BenchmarkService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe((benchmarks) => {
      this.benchmarks = benchmarks.filter(b => b.pairId.startsWith('dblp-'));
    });
  }

  getMethod(pairId: string): string {
    return this.methodMap.get(pairId) || 'AJ';
  }

  setMethod(pairId: string, method: string): void {
    this.methodMap.set(pairId, method);
  }

  toggleSelection(pairId: string): void {
    if (this.selectedIds.has(pairId)) {
      this.selectedIds.delete(pairId);
    } else {
      this.selectedIds.add(pairId);
    }
  }

  runSelected(): void {
    const runs: { pairId: string; method: string }[] = [];
    for (const id of this.selectedIds) {
      const m = this.methodMap.get(id) || 'AJ';
      for (const method of m === 'All' ? this.allMethods : [m]) {
        runs.push({ pairId: id, method });
      }
    }
    this.runQueue(runs);
  }

  runSingle(pairId: string, method: string): void {
    const methods = method === 'All' ? this.allMethods : [method];
    this.runQueue(methods.map((m) => ({ pairId, method: m })));
  }

  // One HTTP request per run, sequentially: a single request holding many
  // long runs would outlive the browser's ~5 min connection limit, while each
  // individual run is bounded by the backend's baseline timeout.
  private runQueue(runs: { pairId: string; method: string }[]): void {
    if (runs.length === 0) return;
    this.running = true;
    const failures: string[] = [];
    let i = 0;
    const next = () => {
      if (i >= runs.length) {
        this.running = false;
        if (failures.length > 0) {
          this.statusMessage = `Done with errors (${failures.join('; ')}) — other results saved.`;
        } else {
          this.router.navigate(['/results']);
        }
        return;
      }
      const run = runs[i++];
      this.statusMessage = `Running ${i}/${runs.length}: ${run.pairId} (${run.method})...`;
      this.benchmarkService.runBenchmark(run.pairId, run.method).subscribe({
        next: () => next(),
        error: (err) => {
          failures.push(`${run.pairId} ${run.method}: ${err.message || 'failed'}`);
          next();
        }
      });
    };
    next();
  }

  selectAll(): void {
    this.benchmarks.forEach(b => this.selectedIds.add(b.pairId));
  }

  deselectAll(): void {
    this.selectedIds.clear();
  }
}