import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { BenchmarkService, SystemInfo } from '../../services/benchmark.service';
import { BenchmarkBase } from '../benchmarks/benchmark-base';

@Component({
  selector: 'app-dblp',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf],
  templateUrl: './dblp.component.html',
  styleUrl: '../benchmarks/benchmarks.component.scss'
})
export class DblpComponent extends BenchmarkBase implements OnInit {
  allMethods = ['AJ', 'SM', 'FJ-C', 'FJ-O'];
  filterPredicate = (b: { pairId: string }) => b.pairId.startsWith('dblp-');
  systemInfo?: SystemInfo;

  constructor(benchmarkService: BenchmarkService, router: Router) {
    super(benchmarkService, router);
    this.methods = [...this.allMethods, 'All'];
  }

  override ngOnInit(): void {
    super.ngOnInit();
    this.benchmarkService.getSystemInfo().subscribe((info) => (this.systemInfo = info));
  }

  get heapGb(): string {
    return this.systemInfo
      ? (this.systemInfo.maxHeapBytes / (1024 * 1024 * 1024)).toFixed(1)
      : '?';
  }
}