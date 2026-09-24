import { Component, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-progress-ring',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (mode === 'bar') {
      <div class="w-full">
        @if (showLabel) {
          <div class="flex items-center justify-between text-xs font-medium mb-1.5">
            <span class="text-neutral-600 dark:text-neutral-400">{{ label }}</span>
            <span [class]="getTextColorClass()">{{ percentage }}%</span>
          </div>
        }
        <div class="w-full h-2 bg-neutral-100 dark:bg-neutral-800 rounded-full overflow-hidden">
          <div
            class="h-full rounded-full transition-all duration-500 ease-out"
            [style.width.%]="clampedPercentage()"
            [style.background-color]="getColor()"
          ></div>
        </div>
      </div>
    } @else {
      <!-- Circular Progress Ring -->
      <div class="relative inline-flex items-center justify-center">
        <svg [attr.width]="size" [attr.height]="size" class="transform -rotate-90">
          <!-- Background track -->
          <circle
            [attr.cx]="center"
            [attr.cy]="center"
            [attr.r]="radius"
            stroke="currentColor"
            class="text-neutral-200 dark:text-neutral-800"
            [attr.stroke-width]="strokeWidth"
            fill="transparent"
          />
          <!-- Active Progress Stroke -->
          <circle
            [attr.cx]="center"
            [attr.cy]="center"
            [attr.r]="radius"
            [attr.stroke]="getColor()"
            [attr.stroke-width]="strokeWidth"
            fill="transparent"
            [attr.stroke-dasharray]="circumference"
            [attr.stroke-dashoffset]="dashOffset()"
            stroke-linecap="round"
            class="transition-all duration-700 ease-out"
          />
        </svg>
        <div class="absolute inset-0 flex flex-col items-center justify-center">
          <span class="font-semibold text-sm" [class]="getTextColorClass()">
            {{ percentage }}%
          </span>
          @if (subLabel) {
            <span class="text-[10px] font-medium text-neutral-400 uppercase tracking-wider">{{ subLabel }}</span>
          }
        </div>
      </div>
    }
  `
})
export class ProgressRingComponent {
  @Input() percentage = 0;
  @Input() mode: 'bar' | 'ring' = 'bar';
  @Input() size = 76;
  @Input() strokeWidth = 6;
  @Input() label = '';
  @Input() subLabel = '';
  @Input() showLabel = true;
  @Input() colorOverride = '';

  get center(): number {
    return this.size / 2;
  }

  get radius(): number {
    return (this.size - this.strokeWidth) / 2;
  }

  get circumference(): number {
    return 2 * Math.PI * this.radius;
  }

  clampedPercentage = computed(() => Math.min(100, Math.max(0, this.percentage)));

  dashOffset = computed(() => {
    const clamped = Math.min(100, Math.max(0, this.percentage));
    return this.circumference - (clamped / 100) * this.circumference;
  });

  getColor(): string {
    if (this.colorOverride) return this.colorOverride;
    if (this.percentage >= 100) return '#EF4444'; // Danger
    if (this.percentage >= 80) return '#F59E0B'; // Warning
    return '#EAB308'; // Signature Gold Accent
  }

  getTextColorClass(): string {
    if (this.percentage >= 100) return 'text-rose-600 dark:text-rose-400 font-semibold';
    if (this.percentage >= 80) return 'text-amber-600 dark:text-amber-400 font-medium';
    return 'text-neutral-900 dark:text-neutral-100 font-medium';
  }
}
