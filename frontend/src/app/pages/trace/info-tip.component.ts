import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-info-tip',
  standalone: true,
  template: `
    <span class="info-tip">
      ⓘ
      <span class="info-tip__box">{{ text }}</span>
    </span>
  `,
  styles: [`
    .info-tip {
      display: inline-flex;
      align-items: center;
      position: relative;
      cursor: help;
      font-size: 0.75rem;
      color: var(--ink-500);
      margin-left: 0.2rem;
      vertical-align: middle;
    }

    .info-tip__box {
      display: none;
      position: absolute;
      bottom: calc(100% + 8px);
      left: 50%;
      transform: translateX(-50%);
      background: var(--ink-900);
      color: #fff;
      font-size: 0.75rem;
      font-weight: 400;
      padding: 0.5rem 0.8rem;
      border-radius: var(--radius-sm);
      white-space: normal;
      width: 240px;
      line-height: 1.4;
      z-index: 20;
      box-shadow: var(--shadow-md);
    }

    .info-tip__box::after {
      content: '';
      position: absolute;
      top: 100%;
      left: 50%;
      transform: translateX(-50%);
      border: 6px solid transparent;
      border-top-color: var(--ink-900);
    }

    .info-tip:hover .info-tip__box {
      display: block;
    }
  `]
})
export class InfoTipComponent {
  @Input() text = '';
}