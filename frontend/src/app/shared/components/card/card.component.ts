import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div
      class="card-brutal overflow-hidden"
      [class.card-brutal-hover]="hoverable"
      [class]="customClass"
    >
      @if (title || headerAccent) {
        <div
          class="border-b border-neutral-200 dark:border-neutral-800 px-5 py-3.5 flex items-center justify-between"
          [style.background-color]="headerAccent || ''"
          [class.bg-neutral-50]="!headerAccent"
          [class.dark:bg-neutral-800-50]="!headerAccent"
        >
          <div class="flex items-center gap-2.5">
            @if (icon) {
              <span class="text-lg">{{ icon }}</span>
            }
            <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-100 tracking-tight">
              {{ title }}
            </h3>
          </div>
          <div class="flex items-center gap-2">
            <ng-content select="[card-action]"></ng-content>
          </div>
        </div>
      }

      <div class="p-5" [class]="bodyClass">
        <ng-content></ng-content>
      </div>

      @if (hasFooter) {
        <div class="border-t border-neutral-200 dark:border-neutral-800 px-5 py-3 bg-neutral-50/70 dark:bg-neutral-800/40">
          <ng-content select="[card-footer]"></ng-content>
        </div>
      }
    </div>
  `
})
export class CardComponent {
  @Input() title = '';
  @Input() icon = '';
  @Input() headerAccent = '';
  @Input() hoverable = false;
  @Input() hasFooter = false;
  @Input() customClass = '';
  @Input() bodyClass = '';
}
