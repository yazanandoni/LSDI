import { Component, Input } from '@angular/core';

export interface FlowStep {
  id: string;
  label: string;
}

@Component({
  selector: 'app-trace-flow-bar',
  standalone: true,
  template: `
    <div class="flow-bar">
      @for (step of steps; track step.id; let last = $last) {
        <div
          class="flow-bar__step"
          [class.flow-bar__step--active]="step.id === activeStep"
          [class.flow-bar__step--completed]="completedSteps.has(step.id)"
        >
          <div class="flow-bar__dot"></div>
          <span class="flow-bar__label">{{ step.label }}</span>
          @if (!last) {
            <div class="flow-bar__arrow">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .flow-bar {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 1rem 1.5rem;
      background: rgba(255, 255, 255, 0.95);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      border: 1px solid rgba(15, 76, 92, 0.08);
      backdrop-filter: blur(8px);
      overflow-x: auto;
    }

    .flow-bar__step {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      flex-shrink: 0;
    }

    .flow-bar__dot {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background: rgba(15, 76, 92, 0.12);
      border: 2px solid rgba(15, 76, 92, 0.2);
      transition: all 0.3s ease;
    }

    .flow-bar__step--active .flow-bar__dot {
      background: var(--ocean-500);
      border-color: var(--ocean-500);
      box-shadow: 0 0 0 4px rgba(28, 107, 125, 0.2);
    }

    .flow-bar__step--completed .flow-bar__dot {
      background: var(--moss-500);
      border-color: var(--moss-500);
    }

    .flow-bar__label {
      font-size: 0.82rem;
      font-weight: 600;
      color: var(--ink-500);
      white-space: nowrap;
    }

    .flow-bar__step--active .flow-bar__label {
      color: var(--ocean-500);
    }

    .flow-bar__step--completed .flow-bar__label {
      color: var(--moss-500);
    }

    .flow-bar__arrow {
      color: rgba(15, 76, 92, 0.25);
      display: flex;
      align-items: center;
    }
  `]
})
export class TraceFlowBarComponent {
  @Input() steps: FlowStep[] = [];
  @Input() activeStep = '';
  @Input() completedSteps: Set<string> = new Set();
}