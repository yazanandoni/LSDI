import { OnInit, Directive } from '@angular/core';
import { Router } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

@Directive()
export abstract class BenchmarkBase implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  abstract allMethods: string[];
  abstract filterPredicate: (b: BenchmarkDescriptor) => boolean;
  methods: string[] = [];
  selectedIds = new Set<string>();
  methodMap = new Map<string, string>();
  running = false;
  statusMessage = '';

  constructor(
    protected benchmarkService: BenchmarkService,
    protected router: Router
  ) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe((benchmarks) => {
      this.benchmarks = benchmarks.filter(this.filterPredicate);
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