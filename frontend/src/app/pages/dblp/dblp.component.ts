import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService, SystemInfo } from '../../services/benchmark.service';
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
  systemInfo?: SystemInfo;

  constructor(
    private benchmarkService: BenchmarkService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe((benchmarks) => {
      this.benchmarks = benchmarks.filter(b => b.pairId.startsWith('dblp-'));
    });
    this.benchmarkService.getSystemInfo().subscribe((info) => (this.systemInfo = info));
  }

  get heapGb(): string {
    return this.systemInfo
      ? (this.systemInfo.maxHeapBytes / (1024 * 1024 * 1024)).toFixed(1)
      : '?';
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

  // Runs execute sequentially via the async job API (start + poll) so no HTTP
  // connection is held open for the duration of a run — long runs otherwise
  // die at the browser's connection limit ("status 0 Unknown Error").
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