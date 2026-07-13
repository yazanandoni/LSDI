import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

/**
 * Synthetic benchmark page (paper sec. 6.3.3): the 4 cases reconstructed from
 * Warren & Tompa (VLDB 2006) — UserID, Time, NameConcat, Citeseer. Generated
 * by scripts/synthetic_benchmark.py; all 8 sec. 6.2 methods are runnable, as
 * in the paper's Figure 9d. Mirrors the Benchmarks page, filtered to
 * synthetic-* fixtures.
 */
@Component({
  selector: 'app-synthetic',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf, FormsModule],
  templateUrl: './synthetic.component.html',
  styleUrl: '../benchmarks/benchmarks.component.scss'
})
export class SyntheticComponent implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  // Paper Table 2 / Figure 9d evaluate the Synthetic benchmark without AJ-E.
  allMethods = ['AJ', 'SM', 'DQ-P', 'DQ-R', 'FJ-C', 'FJ-FR', 'FJ-O'];
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
      this.benchmarks = benchmarks.filter(b => b.pairId.startsWith('synthetic-'));
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
