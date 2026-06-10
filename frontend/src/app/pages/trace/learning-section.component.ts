import { Component, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { LearningTrace } from './trace.models';
import { InfoTipComponent } from './info-tip.component';

@Component({
  selector: 'app-learning-section',
  standalone: true,
  imports: [DecimalPipe, InfoTipComponent],
  template: `
    <div class="learning">
      @if (trace) {
        <div class="learning__header">
          <div>
            <h3>2. Transformation Learning</h3>
            <p class="helper">
              Learning a transformation program from example row pairs.
              Column: {{ trace.sourceColumnName }} → Column: {{ trace.targetColumnName }}
            </p>
          </div>
        </div>

        <div class="learning__score">
          <span class="score-label">Injective Score
            <app-info-tip text="Number of source rows that form a unique 1:1 connection to a target row. Maximum equals the number of source rows." />
          </span>
          <div class="score-bar-wrapper">
            <div class="score-bar">
              <div class="score-bar__fill" [style.width.%]="scorePercent"
                   [style.background]="scoreColor"></div>
            </div>
            <span class="score-value">{{ trace.injectiveScore }} / {{ trace.totalSourceRows }}</span>
          </div>
        </div>

        <div class="card learning__demo">
          <h4>Transformation in Action</h4>
          <p class="helper">
            See how the learned program transforms a source value step by step.
          </p>

          <div class="demo-input">
            <span class="demo-label">Input:</span>
            <span class="demo-value">{{ trace.demoInput }}</span>
          </div>

          <div class="demo-steps">
            @for (step of trace.transformDemo; track $index) {
              <div class="demo-step" [style.animation-delay]="($index + 1) * 0.4 + 's'">
                <div class="demo-step__arrow">↓</div>
                <div class="operator-card">
                  <div class="operator-card__header">
                    <span class="operator-type-badge">{{ step.operatorType }}</span>
                    <span class="operator-desc">{{ step.operatorDescription }}</span>
                  </div>
                  <div class="operator-card__params">
                    @for (entry of objectEntries(step.params); track entry.key) {
                      <span class="param-tag">
                        <span class="param-tag__key">{{ entry.key }}:</span>
                        <span class="param-tag__val">{{ entry.value }}</span>
                      </span>
                    }
                  </div>
                  <div class="demo-acum">"{{ step.output }}"</div>
                </div>
              </div>
            }
          </div>

          <div class="demo-result" [style.animation-delay]="(trace.transformDemo.length + 1) * 0.4 + 's'">
            <div class="demo-step__arrow">═</div>
            <div class="demo-result__box">
              <div class="demo-result__row">
                <span class="demo-label">Result:</span>
                <span class="demo-value">"{{ fullResult }}"</span>
              </div>
              <div class="demo-result__row">
                <span class="demo-label">Target:</span>
                <span class="demo-value demo-value--target">"{{ trace.demoTarget }}"</span>
              </div>
              <div class="demo-result__badge" [class.match]="matches" [class.no-match]="!matches">
                {{ matches ? '✓ Match' : '✗ No match' }}
              </div>
            </div>
          </div>
        </div>

        @if (trace.demoMatches.length > 0) {
          <div class="card learning__results">
            <h4>More Examples</h4>
            <div class="demo-results-list">
              @for (dm of trace.demoMatches; track $index) {
                <div class="demo-result-item" [class.demo-result-item--ok]="dm.matches"
                     [class.demo-result-item--fail]="!dm.matches">
                  <span class="demo-result-item__icon">{{ dm.matches ? '✓' : '✗' }}</span>
                  <span class="mono">{{ dm.sourceValue }}</span>
                  <span class="demo-arrow">→</span>
                  <span class="mono key">"{{ dm.transformedKey }}"</span>
                  <span class="demo-arrow">→</span>
                  <span class="mono">{{ dm.targetValue || '—' }}</span>
                </div>
              }
            </div>
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .learning {
      display: grid;
      gap: 1.2rem;
    }

    .learning__header h3 {
      margin: 0 0 0.3rem;
    }

    .helper {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0;
    }

    .learning__score {
      display: flex;
      align-items: center;
      gap: 1rem;
      flex-wrap: wrap;
    }

    .score-label {
      font-weight: 600;
      font-size: 0.85rem;
      white-space: nowrap;
    }

    .score-bar-wrapper {
      display: flex;
      align-items: center;
      gap: 0.8rem;
      flex: 1;
    }

    .score-bar {
      flex: 1;
      height: 20px;
      background: rgba(15, 76, 92, 0.1);
      border-radius: 999px;
      overflow: hidden;
      min-width: 120px;
    }

    .score-bar__fill {
      height: 100%;
      border-radius: 999px;
      transition: width 0.5s ease;
    }

    .score-value {
      font-weight: 600;
      font-size: 0.9rem;
      white-space: nowrap;
      color: var(--ink-500);
    }

    .learning__demo {
      padding: 1.2rem;
    }

    .learning__results {
      padding: 1.2rem;
    }

    .learning__results h4 {
      margin: 0 0 0.8rem;
      font-size: 0.9rem;
    }

    .demo-results-list {
      display: grid;
      gap: 0.4rem;
    }

    .demo-result-item {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.4rem 0.6rem;
      border-radius: var(--radius-sm);
      font-size: 0.82rem;
    }

    .demo-result-item--ok {
      background: rgba(88, 129, 87, 0.06);
    }

    .demo-result-item--fail {
      background: rgba(209, 73, 91, 0.06);
    }

    .demo-result-item__icon {
      font-weight: 700;
      width: 20px;
      text-align: center;
    }

    .demo-result-item--ok .demo-result-item__icon {
      color: var(--moss-500);
    }

    .demo-result-item--fail .demo-result-item__icon {
      color: var(--rose-500);
    }

    .demo-arrow {
      color: var(--ink-500);
      font-size: 0.8rem;
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

    .learning__demo h4 {
      margin: 0 0 0.3rem;
      font-size: 0.9rem;
    }

    .demo-input {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 1rem 0 0.5rem;
      padding: 0.8rem 1rem;
      background: rgba(15, 76, 92, 0.04);
      border: 1px solid rgba(15, 76, 92, 0.12);
      border-radius: var(--radius-sm);
    }

    .demo-label {
      font-weight: 600;
      font-size: 0.82rem;
      color: var(--ink-500);
    }

    .demo-value {
      font-family: 'Space Mono', monospace;
      font-size: 0.9rem;
      font-weight: 600;
      color: var(--ink-900);
    }

    .demo-steps {
      display: grid;
      gap: 0;
    }

    .demo-step {
      opacity: 0;
      animation: slideFadeIn 0.5s ease forwards;
    }

    @keyframes slideFadeIn {
      from {
        opacity: 0;
        transform: translateY(12px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }

    .demo-step__arrow {
      font-size: 1.2rem;
      color: var(--ocean-300);
      padding: 0.3rem 0;
    }

    .operator-card {
      border: 1px solid rgba(15, 76, 92, 0.12);
      border-radius: var(--radius-sm);
      padding: 0.8rem 1rem;
      background: rgba(15, 76, 92, 0.03);
    }

    .operator-card__header {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin-bottom: 0.5rem;
    }

    .operator-type-badge {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      padding: 0.15rem 0.5rem;
      border-radius: 4px;
      background: var(--ocean-500);
      color: white;
    }

    .operator-desc {
      font-family: 'Space Mono', monospace;
      font-size: 0.82rem;
      color: var(--ink-900);
    }

    .operator-card__params {
      display: flex;
      gap: 0.4rem;
      flex-wrap: wrap;
    }

    .param-tag {
      font-size: 0.72rem;
      padding: 0.15rem 0.5rem;
      border-radius: 4px;
      background: rgba(15, 76, 92, 0.08);
    }

    .param-tag__key {
      font-weight: 600;
      color: var(--ink-700);
    }

    .param-tag__val {
      color: var(--ocean-500);
    }

    .demo-acum {
      margin-top: 0.6rem;
      padding: 0.5rem 0.8rem;
      background: rgba(88, 129, 87, 0.08);
      border: 1px solid rgba(88, 129, 87, 0.2);
      border-radius: var(--radius-sm);
      font-family: 'Space Mono', monospace;
      font-size: 0.82rem;
      color: var(--moss-500);
    }

    .demo-value--target {
      color: var(--ocean-500);
    }

    .demo-result {
      opacity: 0;
      animation: slideFadeIn 0.5s ease forwards;
    }

    .demo-result__box {
      border: 2px solid var(--ocean-500);
      border-radius: var(--radius-sm);
      padding: 1rem;
      background: rgba(15, 76, 92, 0.04);
      display: grid;
      gap: 0.5rem;
    }

    .demo-result__row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .demo-result__badge {
      margin-top: 0.3rem;
      padding: 0.25rem 1rem;
      border-radius: 999px;
      font-weight: 700;
      font-size: 0.82rem;
      width: fit-content;
    }

    .demo-result__badge.match {
      background: rgba(88, 129, 87, 0.15);
      color: var(--moss-500);
    }

    .demo-result__badge.no-match {
      background: rgba(209, 73, 91, 0.15);
      color: var(--rose-500);
    }
  `]
})
export class LearningSectionComponent {
  @Input() trace: LearningTrace | null = null;

  get scorePercent(): number {
    if (!this.trace || this.trace.totalSourceRows === 0) return 0;
    return (this.trace.injectiveScore / this.trace.totalSourceRows) * 100;
  }

  get scoreColor(): string {
    const pct = this.scorePercent;
    if (pct >= 70) return '#588157';
    if (pct >= 40) return '#f4a261';
    return '#d1495b';
  }

  get fullResult(): string {
    if (!this.trace || this.trace.transformDemo.length === 0) return '';
    return this.trace.transformDemo[this.trace.transformDemo.length - 1].output;
  }

  get matches(): boolean {
    return this.fullResult === this.trace?.demoTarget;
  }

  objectEntries(obj: Record<string, string>): { key: string; value: string }[] {
    return Object.entries(obj).map(([key, value]) => ({ key, value }));
  }
}