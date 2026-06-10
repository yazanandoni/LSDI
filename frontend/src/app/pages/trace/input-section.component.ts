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
            <table class="mini-table">
              <thead>
                <tr>
                  @for (col of trace.source.columnNames; track col) {
                    <th [class.is-key]="trace.source.keyColumns.includes(col)">
                      {{ col }}
                      @if (trace.source.keyColumns.includes(col)) {
                        <span class="key-badge">key</span>
                      }
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of trace.source.sampleRows; track $index) {
                  <tr>
                    @for (val of row; track $index) {
                      <td>{{ val }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
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
            <table class="mini-table">
              <thead>
                <tr>
                  @for (col of trace.target.columnNames; track col) {
                    <th [class.is-key]="trace.target.keyColumns.includes(col)">
                      {{ col }}
                      @if (trace.target.keyColumns.includes(col)) {
                        <span class="key-badge">key</span>
                      }
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of trace.target.sampleRows; track $index) {
                  <tr>
                    @for (val of row; track $index) {
                      <td>{{ val }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
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
      overflow-x: auto;
    }

    .mini-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.78rem;
    }

    .mini-table th,
    .mini-table td {
      text-align: left;
      padding: 0.25rem 0.6rem 0.25rem 0;
      white-space: nowrap;
      font-family: 'Space Mono', monospace;
      font-size: 0.75rem;
    }

    .mini-table thead th {
      font-weight: 600;
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--ink-500);
      border-bottom: 2px solid rgba(15, 76, 92, 0.15);
      padding-bottom: 0.3rem;
      font-family: 'Space Grotesk', sans-serif;
    }

    .mini-table thead th.is-key {
      color: var(--ocean-500);
    }

    .mini-table tbody td {
      border-bottom: 1px solid rgba(15, 76, 92, 0.04);
    }

    .key-badge {
      font-size: 0.6rem;
      font-weight: 700;
      background: var(--ocean-500);
      color: white;
      padding: 0.05rem 0.35rem;
      border-radius: 3px;
      text-transform: uppercase;
      margin-left: 0.3rem;
      vertical-align: middle;
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