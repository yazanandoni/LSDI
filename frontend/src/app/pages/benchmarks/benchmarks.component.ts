import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { BenchmarkService } from '../../services/benchmark.service';
import { BenchmarkDescriptor } from '../../app.models';

@Component({
  selector: 'app-benchmarks',
  standalone: true,
  imports: [NgFor, NgIf],
  templateUrl: './benchmarks.component.html',
  styleUrl: './benchmarks.component.scss'
})
export class BenchmarksComponent implements OnInit {
  benchmarks: BenchmarkDescriptor[] = [];
  selectedPairId: string | null = null;
  resultId: string | null = null;
  statusMessage = '';

  constructor(private benchmarkService: BenchmarkService) {}

  ngOnInit(): void {
    this.benchmarkService.listBenchmarks().subscribe((benchmarks) => {
      this.benchmarks = benchmarks;
    });
  }

  runBenchmark(pairId: string): void {
    this.selectedPairId = pairId;
    this.statusMessage = 'Running benchmark...';
    this.benchmarkService.runBenchmark(pairId).subscribe((response) => {
      this.resultId = response.resultId;
      this.statusMessage = `Run complete. Result id: ${response.resultId}`;
    });
  }
}
