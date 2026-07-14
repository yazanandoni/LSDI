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
  // The paper's full §6.2 method set is runnable on the Web benchmark
  // (the DBLP pages only expose the §6.4 scalability subset AJ/SM/FJ-C/FJ-O).
  allMethods = ['AJ', 'AJ-E', 'SM', 'DQ-P', 'DQ-R', 'FJ-C', 'FJ-FR', 'FJ-O'];
  methods = [...this.allMethods, 'All'];
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
      this.benchmarks = benchmarks.filter(
        b => !b.pairId.startsWith('dblp-') && !b.pairId.startsWith('synthetic-'));
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

  runAllMethods(): void {
    const runs: { pairId: string; method: string }[] = [];
    for (const b of this.benchmarks) {
      for (const m of this.allMethods) {
        runs.push({ pairId: b.pairId, method: m });
      }
    }
    this.runQueue(runs);
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

  // Runs execute sequentially via the async job API (start + poll) so no HTTP
  // connection is held open for the duration of a run — a long queue (e.g. the
  // FJ-O grid over every pair) otherwise dies at the browser's connection
  // limit ("status 0 Unknown Error").
  private runQueue(runs: { pairId: string; method: string }[]): void {
    if (runs.length === 0) return;
    this.running = true;
    this.benchmarkService.runQueue(runs, (msg) => (this.statusMessage = msg)).then((failures) => {
      this.running = false;
      if (failures.length > 0) {
        this.statusMessage = `Done with errors (${failures.join('; ')}) — other results saved.`;
      } else {
        this.router.navigate(['/results']);
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
