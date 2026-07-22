import { Component } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkBase } from './benchmark-base';

@Component({
  selector: 'app-benchmarks',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf],
  templateUrl: './benchmarks.component.html',
  styleUrl: './benchmarks.component.scss'
})
export class BenchmarksComponent extends BenchmarkBase {
  allMethods = ['AJ', 'AJ-E', 'SM', 'DQ-P', 'DQ-R', 'FJ-C', 'FJ-FR', 'FJ-O'];
  filterPredicate = (b: { pairId: string }) =>
    !b.pairId.startsWith('dblp-') && !b.pairId.startsWith('synthetic-');

  constructor(benchmarkService: BenchmarkService, router: Router) {
    super(benchmarkService, router);
    this.methods = [...this.allMethods, 'All'];
  }
}