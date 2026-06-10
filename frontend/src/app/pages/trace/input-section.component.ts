import { Component, Input } from '@angular/core';
import { InputTablesTrace } from './trace.models';

@Component({
  selector: 'app-input-section',
  standalone: true,
  template: `
    @if (trace) {
      <div class="input-header">
        <h3>Input Tables</h3>
        <p class="helper">Source and target tables loaded for this benchmark.</p>
      </div>
      <div class="tables-grid">
        <div class="card table-card">
          <div class="table-card__head">
            <span class="table-card__name">{{ trace.source.name }}</span>
            <span class="table-card__dims">{{ trace.source.rows }} rows · {{ trace.source.columns }} cols</span>
          </div>
          <div class="table-card__body">
            <div class="mini-table">
              <div class="mini-table__header">
                @for (col of trace.source.columnNames; track col) {
                  <span class="mini-table__th" [class.is-key]="trace.source.keyColumns.includes(col)">
                    {{ col }}
                    @if (trace.source.keyColumns.includes(col)) {
                      <span class="key-badge">key</span>
                    }
                  </span>
                }
              </div>
              @for (row of trace.source.sampleRows; track $index) {
                <div class="mini-table__row">
                  @for (val of row; track $index) {
                    <span class="mini-table__td">{{ val }}</span>
                  }
                </div>
              }
            </div>
            @if (trace.source.rows > 5) {
              <p class="mini-table__more">… and {{ trace.source.rows - 5 }} more rows</p>
            }
          </div>
        </div>

        <div class="card table-card">
          <div class="table-card__head">
            <span class="table-card__name">{{ trace.target.name }}</span>
            <span class="table-card__dims">{{ trace.target.rows }} rows · {{ trace.target.columns }} cols</span>
          </div>
          <div class="table-card__body">
            <div class="mini-table">
              <div class="mini-table__header">
                @for (col of trace.target.columnNames; track col) {
                  <span class="mini-table__th" [class.is-key]="trace.target.keyColumns.includes(col)">
                    {{ col }}
                    @if (trace.target.keyColumns.includes(col)) {
                      <span class="key-badge">key</span>
                    }
                  </span>
                }
              </div>
              @for (row of trace.target.sampleRows; track $index) {
                <div class="mini-table__row">
                  @for (val of row; track $index) {
                    <span class="mini-table__td">{{ val }}</span>
                  }
                </div>
              }
            </div>
            @if (trace.target.rows > 5) {
              <p class="mini-table__more">… and {{ trace.target.rows - 5 }} more rows</p>
            }
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .input-header {
      margin-bottom: 1rem;
    }

    .input-header h3 {
      margin: 0 0 0.3rem;
    }

    .helper {
      color: var(--ink-500);
      font-size: 0.88rem;
      margin: 0;
    }

    .tables-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1rem;
    }

    .table-card {
      padding: 0;
      overflow: hidden;
    }

    .table-card__head {
      padding: 0.8rem 1rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid rgba(15, 76, 92, 0.08);
      background: rgba(15, 76, 92, 0.03);
    }

    .table-card__name {
      font-weight: 700;
      font-size: 0.9rem;
    }

    .table-card__dims {
      font-size: 0.75rem;
      color: var(--ink-500);
    }

    .table-card__body {
      padding: 0.8rem 1rem;
    }

    .mini-table {
      display: grid;
      gap: 0;
      font-size: 0.78rem;
    }

    .mini-table__header {
      display: flex;
      gap: 0;
      border-bottom: 2px solid rgba(15, 76, 92, 0.15);
      padding-bottom: 0.3rem;
      margin-bottom: 0.2rem;
    }

    .mini-table__th {
      flex: 1;
      font-weight: 600;
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--ink-500);
      display: flex;
      align-items: center;
      gap: 0.3rem;
    }

    .mini-table__th.is-key {
      color: var(--ocean-500);
    }

    .key-badge {
      font-size: 0.6rem;
      font-weight: 700;
      background: var(--ocean-500);
      color: white;
      padding: 0.05rem 0.35rem;
      border-radius: 3px;
      text-transform: uppercase;
    }

    .mini-table__row {
      display: flex;
      gap: 0;
      padding: 0.2rem 0;
      border-bottom: 1px solid rgba(15, 76, 92, 0.04);
    }

    .mini-table__td {
      flex: 1;
      font-family: 'Space Mono', monospace;
      font-size: 0.75rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mini-table__more {
      font-size: 0.72rem;
      color: var(--ink-500);
      margin: 0.4rem 0 0;
      font-style: italic;
    }

    @media (max-width: 800px) {
      .tables-grid {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class InputSectionComponent {
  @Input() trace: InputTablesTrace | null = null;
}