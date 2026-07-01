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
    const ids: string[] = [];
    const methods: string[] = [];
    for (const id of this.selectedIds) {
      const m = this.methodMap.get(id) || 'AJ';
      if (m === 'All') {
        for (const am of this.allMethods) {
          ids.push(id);
          methods.push(am);
        }
      } else {
        ids.push(id);
        methods.push(m);
      }
    }
    if (ids.length === 0) return;
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
    const methods = method === 'All' ? this.allMethods : [method];
    const ids = methods.map(() => pairId);
    this.running = true;
    this.statusMessage = `Running ${pairId} (${method})...`;
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

  selectAll(): void {
    this.benchmarks.forEach(b => this.selectedIds.add(b.pairId));
  }

  deselectAll(): void {
    this.selectedIds.clear();
  }
}