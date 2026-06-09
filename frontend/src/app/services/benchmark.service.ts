import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../core/api.config';
import {
  BenchmarkDescriptor,
  BenchmarkSummaryView,
  ResultIdResponse
} from '../app.models';
import { AlgorithmTrace } from '../pages/trace/trace.models';

@Injectable({ providedIn: 'root' })
export class BenchmarkService {
  constructor(private http: HttpClient) {}

  listBenchmarks(): Observable<BenchmarkDescriptor[]> {
    return this.http.get<BenchmarkDescriptor[]>(`${API_BASE_URL}/benchmarks`);
  }

  runBenchmark(pairId: string): Observable<ResultIdResponse> {
    return this.http.post<ResultIdResponse>(`${API_BASE_URL}/benchmarks/run`, { pairId });
  }

  runAllBenchmarks(): Observable<ResultIdResponse[]> {
    return this.http.post<ResultIdResponse[]>(`${API_BASE_URL}/benchmarks/run-all`, {});
  }

  runBatch(pairIds: string[]): Observable<ResultIdResponse[]> {
    return this.http.post<ResultIdResponse[]>(`${API_BASE_URL}/benchmarks/run-batch`, { pairIds });
  }

  listResults(): Observable<BenchmarkSummaryView[]> {
    return this.http.get<BenchmarkSummaryView[]>(`${API_BASE_URL}/results`);
  }

  getResult(id: string): Observable<BenchmarkSummaryView> {
    return this.http.get<BenchmarkSummaryView>(`${API_BASE_URL}/results/${id}`);
  }

  getTrace(id: string): Observable<AlgorithmTrace> {
    return this.http.get<AlgorithmTrace>(`${API_BASE_URL}/results/${id}/trace`);
  }
}