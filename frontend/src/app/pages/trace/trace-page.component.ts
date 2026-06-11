import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import { BenchmarkService } from '../../services/benchmark.service';
import { AlgorithmTrace } from './trace.models';
import { DiscoverySectionComponent } from './discovery-section.component';
import { LearningSectionComponent } from './learning-section.component';
import { ApplicationSectionComponent } from './application-section.component';
import { InputSectionComponent } from './input-section.component';

@Component({
  selector: 'app-trace-page',
  standalone: true,
  imports: [RouterLink, DecimalPipe, DiscoverySectionComponent, LearningSectionComponent, ApplicationSectionComponent, InputSectionComponent],
  template: `
    <section class="container section">
      <a class="back-link" [routerLink]="backLink">← Back to {{ backLabel }}</a>
      <div class="section__header">
        <div>
          <p class="eyebrow">Algorithm Trace</p>
          @if (resultId) {
            <h2>Benchmark: {{ traceResult?.pairId || resultId }}</h2>
          }
        </div>
      </div>

      @if (trace) {
        <div class="trace-content">
          <div class="card trace-section" id="step-input">
            <app-input-section [trace]="trace.inputTables" />
          </div>

          <div class="trace-section-connector"></div>

          <div class="card trace-section" id="step-discovery">
            <app-discovery-section [trace]="winningDiscovery" />
          </div>

          <div class="trace-section-connector"></div>

          <div class="card trace-section" id="step-learning">
            <app-learning-section [trace]="winningLearning" />
          </div>

          <div class="trace-section-connector"></div>

          <div class="card trace-section" id="step-apply">
            <app-application-section [trace]="winningApplication" />
          </div>

          <div class="trace-section-connector"></div>

          <div class="card trace-section" id="step-results">
            <h3>Results Summary</h3>
            <p class="helper">Final join outcome with precision and recall metrics.</p>
            @if (traceResult) {
              <div class="placeholder">
                <div class="result-metrics">
                  <div>
                    <span>Precision</span>
                    <strong>{{ traceResult.precision | number:'1.2-2' }}</strong>
                  </div>
                  <div>
                    <span>Recall</span>
                    <strong>{{ traceResult.recall | number:'1.2-2' }}</strong>
                  </div>
                  <div>
                    <span>Duration</span>
                    <strong>{{ traceResult.durationMs }}ms</strong>
                  </div>
                  <div>
                    <span>Direction</span>
                    <strong>{{ trace.forwardWon ? 'source → target' : 'target → source' }}</strong>
                  </div>
                </div>
              </div>
            }
            @if (traceResult) {
              <a class="btn btn--ghost" [routerLink]="['/results']">View full results →</a>
            }
          </div>
        </div>
      } @else {
        <div class="card empty">
          @if (!error) {
            <p>{{ loading ? 'Loading trace data...' : 'No trace data available for this result.' }}</p>
          }
          @if (error) {
            <p class="error">Failed to load trace: {{ error }}</p>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .section {
      padding: 1rem 0 3rem;
    }

    .back-link {
      display: inline-block;
      font-size: 0.85rem;
      color: var(--ocean-500);
      margin-bottom: 0.75rem;
      font-weight: 500;
    }

    .back-link:hover {
      text-decoration: underline;
    }

    .section__header {
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      gap: 1.5rem;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
    }

    .eyebrow {
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.12em;
      color: var(--ocean-500);
      margin: 0;
    }

    .section__header h2 {
      margin: 0.4rem 0 0;
    }

    .trace-content {
      display: grid;
      gap: 0;
    }

    .trace-section {
      padding: 1.5rem;
    }

    .trace-section h3 {
      margin: 0 0 0.3rem;
    }

    .helper {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0 0 1rem;
    }

    .trace-section-connector {
      height: 32px;
      display: flex;
      justify-content: center;
      align-items: center;
      position: relative;
    }

    .trace-section-connector::after {
      content: '';
      width: 2px;
      height: 100%;
      background: linear-gradient(to bottom, var(--ocean-300), var(--ocean-500));
      border-radius: 2px;
    }

    .placeholder {
      padding: 1.5rem;
      background: rgba(244, 162, 97, 0.06);
      border: 1px dashed rgba(244, 162, 97, 0.3);
      border-radius: var(--radius-sm);
      text-align: center;
      color: var(--ink-500);
    }

    .result-metrics {
      display: flex;
      gap: 2rem;
      justify-content: center;
      flex-wrap: wrap;
    }

    .result-metrics span {
      display: block;
      font-size: 0.75rem;
      color: var(--ink-500);
      margin-bottom: 0.3rem;
    }

    .result-metrics strong {
      font-size: 1.3rem;
    }

    .empty {
      padding: 2rem;
      text-align: center;
      color: var(--ink-500);
    }

    .error {
      color: var(--rose-500);
    }

    .btn--ghost {
      display: inline-flex;
      align-items: center;
      padding: 0.5rem 1rem;
      border-radius: 999px;
      font-weight: 600;
      font-size: 0.82rem;
      border: 1px solid var(--ocean-500);
      color: var(--ocean-500);
      background: transparent;
      cursor: pointer;
      transition: all 0.2s ease;
      text-decoration: none;
      margin-top: 1rem;
    }

    .btn--ghost:hover {
      background: rgba(15, 76, 92, 0.08);
    }
  `]
})
export class TracePageComponent implements OnInit {
  resultId: string | null = null;
  trace: AlgorithmTrace | null = null;
  traceResult: any = null;
  loading = true;
  error: string | null = null;
  backLink = '/results';
  backLabel = 'Results';

  get winningDiscovery() {
    if (!this.trace) return null;
    const dirTrace = this.trace.forwardWon ? this.trace.forwardTrace : this.trace.backwardTrace;
    return dirTrace.discovery;
  }

  get winningLearning() {
    if (!this.trace) return null;
    const dirTrace = this.trace.forwardWon ? this.trace.forwardTrace : this.trace.backwardTrace;
    return dirTrace.learning;
  }

  get winningApplication() {
    if (!this.trace) return null;
    const dirTrace = this.trace.forwardWon ? this.trace.forwardTrace : this.trace.backwardTrace;
    return dirTrace.application;
  }

  constructor(
    private route: ActivatedRoute,
    private benchmarkService: BenchmarkService
  ) {}

  ngOnInit(): void {
    this.resultId = this.route.snapshot.paramMap.get('resultId');

    const referrer = this.route.snapshot.queryParamMap.get('from');
    if (referrer === 'dashboard') {
      this.backLink = '';
      this.backLabel = 'Dashboard';
    }

    if (this.resultId) {
      this.benchmarkService.getTrace(this.resultId).subscribe({
        next: (trace) => {
          this.trace = trace;
          this.loading = false;
        },
        error: (err) => {
          this.error = err.message || 'Unknown error';
          this.loading = false;
        }
      });

      this.benchmarkService.getResult(this.resultId).subscribe({
        next: (result) => {
          this.traceResult = result;
        }
      });
    }
  }
}