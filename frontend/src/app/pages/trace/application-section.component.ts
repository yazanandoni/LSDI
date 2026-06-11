import { Component, Input } from '@angular/core';
import { ApplicationTrace } from './trace.models';
import { InfoTipComponent } from './info-tip.component';

@Component({
  selector: 'app-application-section',
  standalone: true,
  imports: [InfoTipComponent],
  template: `
    <div class="application">
      @if (trace) {
        <div class="application__header">
          <div>
            <h3>3. Join Application</h3>
            <p class="helper">
              Applying the learned transformation as an equi-join.
            </p>
          </div>
        </div>

        <div class="flow-diagram">
          <div class="flow-node">
            <div class="flow-node__count">{{ trace.totalSourceRows }}</div>
            <div class="flow-node__label">Source Rows</div>
          </div>
          <div class="flow-arrow">
            <svg width="32" height="24" viewBox="0 0 32 24">
              <path d="M4 12h20M24 6l6 6-6 6" stroke="var(--ocean-300)" stroke-width="2" fill="none" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="flow-node">
            <div class="flow-node__count">{{ trace.totalMatched }}</div>
            <div class="flow-node__label">Transformed Keys</div>
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
                <div class="flow-node__count">{{ trace.totalMatched }}</div>
                <div class="flow-node__label">Matched ✅</div>
              </div>
            </div>
            <div class="flow-split__branch">
              <div class="flow-split__arrow">↓</div>
              <div class="flow-node flow-node--warn">
                <div class="flow-node__count">{{ trace.totalUnmatched }}</div>
                <div class="flow-node__label">Unmatched ⚠️</div>
              </div>
            </div>
          </div>
        </div>

        @if (trace.sampleMatches.length > 0) {
          <div class="card sample-table">
            <h4>Sample Matches</h4>
            <div class="sample-table__header">
              <span>Source Value</span>
              <span>Transformed Key <app-info-tip text="The result of applying the learned transformation to the source value. This derived key is matched against the target column." /></span>
              <span>Target Value</span>
              <span></span>
            </div>
            @for (sample of trace.sampleMatches; track $index) {
              <div class="sample-table__row" [class.unmatched]="sample.status === 'UNMATCHED'">
                <span class="mono">{{ sample.sourceValue }}</span>
                <span class="mono key">"{{ sample.transformedKey }}"</span>
                <span class="mono">{{ sample.matchedTargetValue || '(no entry)' }}</span>
                <span class="status-badge" [class.match]="sample.status === 'MATCHED'"
                      [class.no-match]="sample.status === 'UNMATCHED'">
                  {{ sample.status === 'MATCHED' ? '✓' : '✗' }}
                </span>
              </div>
            }
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .application {
      display: grid;
      gap: 1.5rem;
    }

    .application__header h3 {
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

    .flow-node__count {
      font-size: 1.6rem;
      font-weight: 700;
      color: var(--ink-900);
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

    .sample-table__row.unmatched {
      background: rgba(209, 73, 91, 0.04);
    }

    .mono {
      font-family: 'Space Mono', monospace;
      font-size: 0.78rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .key {
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

    .status-badge.no-match {
      background: rgba(209, 73, 91, 0.15);
      color: var(--rose-500);
    }
  `]
})
export class ApplicationSectionComponent {
  @Input() trace: ApplicationTrace | null = null;
}