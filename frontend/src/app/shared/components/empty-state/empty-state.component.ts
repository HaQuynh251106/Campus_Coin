import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonComponent } from '../button/button.component';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [CommonModule, ButtonComponent, IconComponent],
  template: `
    <div class="card-brutal p-8 text-center max-w-md mx-auto flex flex-col items-center">
      <!-- Real Freely-Licensed Sourced Image (Unsplash) -->
      <div class="relative w-32 h-32 mb-4 rounded-xl border border-neutral-200 dark:border-neutral-800 shadow-xs overflow-hidden bg-neutral-100 dark:bg-neutral-800">
        <img
          [src]="imageUrl"
          [alt]="imageAlt"
          width="128"
          height="128"
          loading="lazy"
          class="w-full h-full object-cover"
        />
      </div>

      <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-100 mb-1.5 tracking-tight">
        {{ title }}
      </h3>

      <p class="text-neutral-500 dark:text-neutral-400 text-xs sm:text-sm max-w-sm mb-5 leading-relaxed">
        {{ description }}
      </p>

      @if (actionLabel) {
        <app-button
          [variant]="buttonVariant"
          (clicked)="actionClicked.emit()"
        >
          @if (actionIcon) {
            <app-icon [name]="actionIcon" [size]="15" strokeWidth="1.5" className="mr-1.5"></app-icon>
          }
          {{ actionLabel }}
        </app-button>
      }
    </div>
  `
})
export class EmptyStateComponent {
  @Input() title = 'No Transactions Recorded Yet';
  @Input() description = 'Start your campus spending streak! Log your first coffee, lunch, or textbook to see smart budget insights.';
  @Input() imageUrl = 'https://images.unsplash.com/photo-1554224155-8d04cb21cd6c?auto=format&fit=crop&w=600&q=80';
  @Input() imageAlt = 'Student budget ledger and desk';
  @Input() actionLabel = '+ Log First Transaction';
  @Input() actionIcon = 'plus';
  @Input() buttonVariant: 'primary' | 'secondary' | 'accent' = 'primary';

  @Output() actionClicked = new EventEmitter<void>();
}
