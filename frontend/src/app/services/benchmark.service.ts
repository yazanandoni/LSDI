import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../core/api.config';
import {
  BenchmarkDescriptor,
  BenchmarkRunRequest,
  BenchmarkSummaryView,
  ResultIdResponse
} from '../app.models';

@Injectable({ providedIn: 'root' })
export class BenchmarkService {
  constructor(private http: HttpClient) {}

  listBenchmarks(): Observable<BenchmarkDescriptor[]> {
    return this.http.get<BenchmarkDescriptor[]>(`${API_BASE_URL}/benchmarks`);
  }

  runBenchmark(pairId: string): Observable<ResultIdResponse> {
    const payload: BenchmarkRunRequest = { pairId };
    return this.http.post<ResultIdResponse>(`${API_BASE_URL}/benchmarks/run`, payload);
  }

  listResults(): Observable<BenchmarkSummaryView[]> {
    return this.http.get<BenchmarkSummaryView[]>(`${API_BASE_URL}/results`);
  }

  getResult(id: string): Observable<BenchmarkSummaryView> {
    return this.http.get<BenchmarkSummaryView>(`${API_BASE_URL}/results/${id}`);
  }
}
