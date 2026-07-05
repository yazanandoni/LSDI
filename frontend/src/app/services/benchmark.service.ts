import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { API_BASE_URL } from '../core/api.config';
import {
  BenchmarkDescriptor,
  BenchmarkSummaryView,
  ResultIdResponse
} from '../app.models';
import { AlgorithmTrace } from '../pages/trace/trace.models';

export interface JobStatus {
  status: 'running' | 'done' | 'error';
  resultId?: string;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class BenchmarkService {
  constructor(private http: HttpClient) {}

  listBenchmarks(): Observable<BenchmarkDescriptor[]> {
    return this.http.get<BenchmarkDescriptor[]>(`${API_BASE_URL}/benchmarks`);
  }

  runBenchmark(pairId: string, method = 'AJ'): Observable<ResultIdResponse> {
    return this.http.post<ResultIdResponse>(`${API_BASE_URL}/benchmarks/run`, { pairId, method });
  }

  runAllBenchmarks(): Observable<ResultIdResponse[]> {
    return this.http.post<ResultIdResponse[]>(`${API_BASE_URL}/benchmarks/run-all`, {});
  }

  runBatch(pairIds: string[], methods?: string[]): Observable<ResultIdResponse[]> {
    return this.http.post<ResultIdResponse[]>(`${API_BASE_URL}/benchmarks/run-batch`, { pairIds, methods });
  }

  runBenchmarkAsync(pairId: string, method = 'AJ'): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`${API_BASE_URL}/benchmarks/run-async`, { pairId, method });
  }

  getJobStatus(jobId: string): Observable<JobStatus> {
    return this.http.get<JobStatus>(`${API_BASE_URL}/benchmarks/jobs/${jobId}`);
  }

  /**
   * Run (pairId, method) pairs strictly sequentially WITHOUT holding an HTTP
   * connection open for the duration of a run: browsers abort requests that
   * stay silent for minutes ("status 0 Unknown Error"), so each run is started
   * via run-async and its job polled every few seconds until it finishes.
   * Returns the failure descriptions (empty array = all runs succeeded).
   */
  async runQueue(runs: { pairId: string; method: string }[],
                 onProgress: (message: string) => void): Promise<string[]> {
    const failures: string[] = [];
    for (let i = 0; i < runs.length; i++) {
      const run = runs[i];
      const label = `Running ${i + 1}/${runs.length}: ${run.pairId} (${run.method})`;
      onProgress(`${label}...`);
      try {
        const { jobId } = await firstValueFrom(this.runBenchmarkAsync(run.pairId, run.method));
        const started = Date.now();
        let pollErrors = 0;
        for (;;) {
          await new Promise((resolve) => setTimeout(resolve, 2500));
          let status: JobStatus;
          try {
            status = await firstValueFrom(this.getJobStatus(jobId));
            pollErrors = 0;
          } catch {
            if (++pollErrors >= 5) throw new Error('backend unreachable');
            continue;
          }
          if (status.status === 'done') break;
          if (status.status === 'error') throw new Error(status.error || 'run failed');
          onProgress(`${label}... ${Math.round((Date.now() - started) / 1000)}s`);
        }
      } catch (e) {
        failures.push(`${run.pairId} ${run.method}: ${e instanceof Error ? e.message : 'failed'}`);
      }
    }
    return failures;
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