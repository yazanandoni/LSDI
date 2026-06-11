import { Component, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { DiscoveryTrace } from './trace.models';
import { InfoTipComponent } from './info-tip.component';

@Component({
  selector: 'app-discovery-section',
  standalone: true,
  imports: [DecimalPipe, InfoTipComponent],
  template: `
    <div class="discovery">
      @if (trace) {
      <div class="discovery__header">
        <div>
          <h3>1. Discovery — q-gram Matching</h3>
          <p class="helper">
            Finding joinable row pairs between source and target columns using q-gram suffix arrays.
          </p>
        </div>
        <div class="discovery__stats">
          <div class="stat-badge">
            <strong>{{ trace.columnPairGroups.length }}</strong>
            <span>column pairs</span>
          </div>
          <div class="stat-badge">
            <strong>{{ totalMatches }}</strong>
            <span>total matches</span>
          </div>
        </div>
      </div>

      @for (group of trace.columnPairGroups; track $index) {
        <div class="card pair-card">
          <div class="pair-card__header" (click)="toggleGroup($index)">
            <div class="pair-card__flow">
              <div class="pair-card__col pair-card__col--source">
                <span class="col-dot col-dot--source"></span>
                <span class="col-name">Column: {{ group.sourceColumnName }}</span>
              </div>
              <div class="pair-card__arrow">
                <svg width="80" height="24" viewBox="0 0 80 24">
                  <line x1="0" y1="12" x2="60" y2="12" [attr.stroke]="arrowColor(group.avgScore)" stroke-width="3" stroke-linecap="round"/>
                  <polygon points="60,6 72,12 60,18" [attr.fill]="arrowColor(group.avgScore)"/>
                </svg>
              </div>
              <div class="pair-card__col pair-card__col--target">
                <span class="col-dot col-dot--target"></span>
                <span class="col-name">Column: {{ group.targetColumnName }}</span>
              </div>
            </div>
            <div class="pair-card__meta">
              <span class="badge badge--matches">{{ group.matchCount }} matches</span>
              <span class="badge badge--score">{{ group.avgScore | number:'1.2-2' }} avg score</span>
              <span class="expand-icon">{{ expandedGroups[$index] ? '▾' : '▸' }}</span>
            </div>
          </div>

          @if (expandedGroups[$index]) {
            <div class="pair-card__matches">
              <h4>{{ group.topMatches.length }} example match{{ group.topMatches.length === 1 ? '' : 'es' }}</h4>
              <div class="match-table">
                <div class="match-table__header">
                  <span>Source Value</span>
                  <span>q-gram <app-info-tip text="A contiguous substring of length q. The algorithm searches for shared q-grams between source and target values." /></span>
                  <span>Target Value</span>
                  <span style="text-align: center">Score <app-info-tip text="1/(n·m) — the closer to 1.0, the more unique the match. A score of 1.0 means this q-gram appears exactly once in both source and target." /></span>
                </div>
                @for (match of group.topMatches; track $index) {
                  <div class="match-table__row">
                    <span class="mono">{{ match.sourceValue }}</span>
                    <span class="mono qgram">{{ match.qgram }}</span>
                    <span class="mono">{{ match.targetValue }}</span>
                    <span class="mono score">{{ match.score | number:'1.2-2' }}</span>
                  </div>
                }
              </div>
            </div>
          }
        </div>
      }
      }
    </div>
  `,
  styles: [`
    .discovery {
      display: grid;
      gap: 1.5rem;
    }

    .discovery__header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      flex-wrap: wrap;
    }

    .discovery__header h3 {
      margin: 0 0 0.3rem;
    }

    .helper {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0;
    }

    .discovery__stats {
      display: flex;
      gap: 0.8rem;
      flex-shrink: 0;
    }

    .stat-badge {
      background: rgba(15, 76, 92, 0.08);
      border: 1px solid rgba(15, 76, 92, 0.15);
      border-radius: var(--radius-sm);
      padding: 0.5rem 1rem;
      text-align: center;
      min-width: 90px;
    }

    .stat-badge strong {
      display: block;
      font-size: 1.4rem;
      color: var(--ocean-500);
    }

    .stat-badge span {
      font-size: 0.7rem;
      color: var(--ink-500);
      text-transform: uppercase;
      letter-spacing: 0.06em;
    }

    .pair-card {
      padding: 0;
      overflow: hidden;
    }

    .pair-card__header {
      padding: 1.2rem 1.5rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
      cursor: pointer;
      transition: background 0.2s ease;
    }

    .pair-card__header:hover {
      background: rgba(15, 76, 92, 0.03);
    }

    .pair-card__flow {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex: 1;
    }

    .pair-card__col {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      min-width: 0;
    }

    .col-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      flex-shrink: 0;
    }

    .col-dot--source {
      background: var(--sun-500);
    }

    .col-dot--target {
      background: var(--ocean-500);
    }

    .col-name {
      font-weight: 600;
      font-size: 0.95rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .pair-card__arrow {
      flex-shrink: 0;
      display: flex;
      align-items: center;
    }

    .pair-card__meta {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      flex-shrink: 0;
    }

    .badge {
      font-size: 0.72rem;
      font-weight: 600;
      padding: 0.25rem 0.65rem;
      border-radius: 999px;
      white-space: nowrap;
    }

    .badge--matches {
      background: rgba(15, 76, 92, 0.1);
      color: var(--ocean-500);
    }

    .badge--score {
      background: rgba(244, 162, 97, 0.15);
      color: var(--sun-500);
    }

    .expand-icon {
      color: var(--ink-500);
      font-size: 0.85rem;
      width: 16px;
      text-align: center;
    }

    .pair-card__matches {
      padding: 0 1.5rem 1.2rem;
      border-top: 1px solid rgba(15, 76, 92, 0.08);
    }

    .pair-card__matches h4 {
      margin: 1rem 0 0.6rem;
      font-size: 0.82rem;
      color: var(--ink-500);
    }

    .match-table {
      display: grid;
      gap: 0;
      border: 1px solid rgba(15, 76, 92, 0.1);
      border-radius: var(--radius-sm);
      overflow: hidden;
    }

    .match-table__header,
    .match-table__row {
      display: grid;
      grid-template-columns: 1fr 1fr 1fr 0.8fr;
      gap: 0.5rem;
      padding: 0.5rem 0.8rem;
      align-items: center;
    }

    .match-table__header {
      background: rgba(15, 76, 92, 0.06);
      font-size: 0.7rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--ink-500);
    }

    .match-table__row {
      font-size: 0.82rem;
      border-top: 1px solid rgba(15, 76, 92, 0.06);
    }

    .match-table__row:hover {
      background: rgba(244, 162, 97, 0.04);
    }

    .mono {
      font-family: 'Space Mono', monospace;
      font-size: 0.78rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .qgram {
      color: var(--ocean-500);
      font-weight: 600;
    }

    .score {
      text-align: center;
      color: var(--ink-500);
    }
  `]
})
export class DiscoverySectionComponent {
  @Input() trace: DiscoveryTrace | null = null;
  expandedGroups: boolean[] = [];

  get totalMatches(): number {
    return this.trace ? this.trace.columnPairGroups.reduce((sum, g) => sum + g.matchCount, 0) : 0;
  }

  arrowColor(score: number): string {
    if (score >= 0.9) return '#1c6b7d';
    if (score >= 0.7) return '#3f8fa3';
    if (score >= 0.4) return '#f4a261';
    return '#e76f51';
  }

  toggleGroup(index: number): void {
    this.expandedGroups[index] = !this.expandedGroups[index];
  }
}