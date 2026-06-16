import { Component, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FuzzyTrace } from './trace.models';
import { InfoTipComponent } from './info-tip.component';

@Component({
  selector: 'app-fuzzy-section',
  standalone: true,
  imports: [DecimalPipe, InfoTipComponent],
  template: `
    @if (trace && trace.recoveredCount > 0) {
      <div class="fuzzy">
        <div class="fuzzy__header">
          <div>
            <h3>4. Fuzzy Join Recovery</h3>
            <p class="helper">
              Recovering rows that almost matched the equi-join key using
              constrained Jaccard-distance matching.
            </p>
          </div>
        </div>

        <div class="flow-diagram">
          <div class="flow-node flow-node--warn">
            <div class="flow-node__count">{{ trace.unmatchedBeforeFuzzy }}</div>
            <div class="flow-node__label">Unmatched after equi-join</div>
          </div>
          <div class="flow-arrow">
            <svg width="32" height="24" viewBox="0 0 32 24">
              <path d="M4 12h20M24 6l6 6-6 6" stroke="var(--ocean-300)" stroke-width="2" fill="none" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="flow-node flow-node--fuzzy">
            <div class="flow-node__threshold">threshold {{ trace.optimalThreshold | number:'1.4-4' }}</div>
            <div class="flow-node__label">Fuzzy Join</div>
          </div>
          <div class="flow-arrow">
            <svg width="32" height="24" viewBox="0 0 32 24">
              <path d="M4 12h20M24 6l6 6-6 6" stroke="var(--ocean-300)" stroke-width="2" fill="none" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="flow-split">
            <div class="flow-split__branch">
              <div class="flow-split__arrow">↓</div>
              <div class="flow-node flow-node--success">
                <div class="flow-node__count">{{ trace.recoveredCount }}</div>
                <div class="flow-node__label">Recovered ✓</div>
              </div>
            </div>
            <div class="flow-split__branch">
              <div class="flow-split__arrow">↓</div>
              <div class="flow-node flow-node--warn">
                <div class="flow-node__count">{{ trace.remainingUnmatched }}</div>
                <div class="flow-node__label">Still unmatched</div>
              </div>
            </div>
          </div>
        </div>

        @if (trace.sampleRecovered.length > 0) {
          <div class="card sample-table">
            <h4>Sample Recovered Pairs</h4>
            <div class="sample-table__header">
              <span>Source Value</span>
              <span>Target Value</span>
              <span>Jaccard Distance <app-info-tip text="Lower is closer. Values within the threshold (≤ {{ trace.optimalThreshold | number:'1.4-4' }}) were recovered as matches." /></span>
              <span></span>
            </div>
            @for (sample of trace.sampleRecovered; track $index) {
              <div class="sample-table__row">
                <span class="mono">{{ sample.sourceValue }}</span>
                <span class="mono">{{ sample.targetValue }}</span>
                <span class="mono distance">{{ sample.jaccardDistance | number:'1.4-4' }}</span>
                <span class="status-badge match">✓</span>
              </div>
            }
          </div>
        }
      </div>
    } @else if (trace) {
      <div class="fuzzy">
        <div class="fuzzy__header">
          <div>
            <h3>4. Fuzzy Join Recovery</h3>
            <p class="helper">
              Recovering rows that almost matched the equi-join key using
              constrained Jaccard-distance matching.
            </p>
          </div>
        </div>
        <div class="flow-diagram flow-diagram--empty">
          <p class="empty-msg">No unmatched rows to recover — the equi-join covered all source rows.</p>
        </div>
      </div>
    }
  `,
  styles: [`
    .fuzzy {
      display: grid;
      gap: 1.5rem;
    }

    .fuzzy__header h3 {
      margin: 0 0 0.3rem;
    }

    .helper {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0;
    }

    .flow-diagram {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      flex-wrap: wrap;
      padding: 1.5rem;
      background: rgba(15, 76, 92, 0.03);
      border: 1px solid rgba(15, 76, 92, 0.1);
      border-radius: var(--radius-sm);
    }

    .flow-diagram--empty {
      padding: 1rem 1.5rem;
    }

    .empty-msg {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0;
    }

    .flow-node {
      text-align: center;
      padding: 0.8rem 1.2rem;
      border-radius: var(--radius-sm);
      background: white;
      border: 2px solid var(--ocean-300);
      min-width: 100px;
    }

    .flow-node--success {
      border-color: var(--moss-500);
    }

    .flow-node--warn {
      border-color: var(--sun-500);
    }

    .flow-node--fuzzy {
      border-color: var(--ocean-500);
      background: rgba(15, 76, 92, 0.04);
    }

    .flow-node__count {
      font-size: 1.6rem;
      font-weight: 700;
      color: var(--ink-900);
    }

    .flow-node__threshold {
      font-size: 0.72rem;
      font-weight: 600;
      color: var(--ocean-500);
      margin-bottom: 0.2rem;
      white-space: nowrap;
    }

    .flow-node__label {
      font-size: 0.75rem;
      color: var(--ink-500);
      white-space: nowrap;
    }

    .flow-arrow {
      display: flex;
      align-items: center;
    }

    .flow-split {
      display: flex;
      gap: 1rem;
      align-items: stretch;
    }

    .flow-split__branch {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.3rem;
    }

    .flow-split__arrow {
      font-size: 1.2rem;
      color: var(--ocean-300);
    }

    .sample-table {
      padding: 1.2rem;
    }

    .sample-table h4 {
      margin: 0 0 0.8rem;
      font-size: 0.9rem;
    }

    .sample-table__header {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr auto;
      gap: 0.5rem;
      padding: 0.5rem 0.8rem;
      background: rgba(15, 76, 92, 0.06);
      border-radius: var(--radius-sm) var(--radius-sm) 0 0;
      font-size: 0.7rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--ink-500);
    }

    .sample-table__row {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr auto;
      gap: 0.5rem;
      padding: 0.6rem 0.8rem;
      border-bottom: 1px solid rgba(15, 76, 92, 0.06);
      align-items: center;
    }

    .sample-table__row:last-child {
      border-bottom: none;
    }

    .mono {
      font-family: 'Space Mono', monospace;
      font-size: 0.78rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .distance {
      color: var(--ocean-500);
    }

    .status-badge {
      font-weight: 700;
      font-size: 0.85rem;
      width: 24px;
      height: 24px;
      display: grid;
      place-items: center;
      border-radius: 50%;
    }

    .status-badge.match {
      background: rgba(88, 129, 87, 0.15);
      color: var(--moss-500);
    }
  `]
})
export class FuzzySectionComponent {
  @Input() trace: FuzzyTrace | null = null;
}