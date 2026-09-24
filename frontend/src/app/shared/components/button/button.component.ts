import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';

export type ButtonVariant = 'primary' | 'secondary' | 'accent' | 'cyan' | 'purple' | 'white';

@Component({
  selector: 'app-button',
  standalone: true,
  imports: [CommonModule],
  template: `
    <button
      [type]="type"
      [disabled]="disabled || loading"
      (click)="clicked.emit($event)"
      [class]="getButtonClasses()"
    >
      @if (loading) {
        <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
        </svg>
      }
      <ng-content></ng-content>
    </button>
  `
})
export class ButtonComponent {
  @Input() variant: ButtonVariant = 'primary';
  @Input() type: 'button' | 'submit' | 'reset' = 'button';
  @Input() disabled = false;
  @Input() loading = false;
  @Input() customClass = '';

  @Output() clicked = new EventEmitter<MouseEvent>();

  getButtonClasses(): string {
    const base = 'btn-brutal';
    const variantMap: Record<ButtonVariant, string> = {
      primary: 'btn-brutal-primary',
      secondary: 'btn-brutal-secondary',
      accent: 'btn-brutal-accent',
      cyan: 'btn-brutal-cyan',
      purple: 'btn-brutal-purple',
      white: 'btn-brutal-white'
    };

    return `${base} ${variantMap[this.variant] || 'btn-brutal-primary'} ${this.customClass}`;
  }
}
