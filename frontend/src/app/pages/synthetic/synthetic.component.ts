import { Component } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkBase } from '../benchmarks/benchmark-base';

@Component({
  selector: 'app-synthetic',
  standalone: true,
  imports: [RouterLink, NgFor, NgIf],
  templateUrl: './synthetic.component.html',
  styleUrl: '../benchmarks/benchmarks.component.scss'
})
export class SyntheticComponent extends BenchmarkBase {
  allMethods = ['AJ', 'SM', 'DQ-P', 'DQ-R', 'FJ-C', 'FJ-FR', 'FJ-O'];
  filterPredicate = (b: { pairId: string }) => b.pairId.startsWith('synthetic-');

  constructor(benchmarkService: BenchmarkService, router: Router) {
    super(benchmarkService, router);
    this.methods = [...this.allMethods, 'All'];
  }
}