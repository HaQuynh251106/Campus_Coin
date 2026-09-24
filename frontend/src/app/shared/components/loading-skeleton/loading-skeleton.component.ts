import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-loading-skeleton',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (type === 'card') {
      <div class="card-brutal p-5 animate-pulse bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800">
        <div class="h-3.5 bg-neutral-200 dark:bg-neutral-800 rounded-md w-1/4 mb-4"></div>
        <div class="h-7 bg-neutral-200 dark:bg-neutral-800 rounded-md w-1/2 mb-3"></div>
        <div class="h-3 bg-neutral-150 dark:bg-neutral-850 rounded-md w-full mb-2"></div>
        <div class="h-3 bg-neutral-150 dark:bg-neutral-850 rounded-md w-4/5"></div>
      </div>
    } @else if (type === 'row') {
      <div class="flex items-center gap-3 p-3.5 card-brutal animate-pulse bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 mb-2">
        <div class="w-9 h-9 rounded-lg bg-neutral-200 dark:bg-neutral-800 shrink-0"></div>
        <div class="flex-1">
          <div class="h-3.5 bg-neutral-200 dark:bg-neutral-800 rounded w-1/2 mb-1.5"></div>
          <div class="h-2.5 bg-neutral-150 dark:bg-neutral-850 rounded w-1/4"></div>
        </div>
        <div class="h-3.5 bg-neutral-200 dark:bg-neutral-800 rounded w-14"></div>
      </div>
    } @else if (type === 'chart') {
      <div class="card-brutal p-6 animate-pulse bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 h-64 flex flex-col justify-end gap-2">
        <div class="h-3.5 bg-neutral-200 dark:bg-neutral-800 rounded w-1/4 mb-auto"></div>
        <div class="flex items-end gap-3 h-40 pt-4">
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-40"></div>
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-28"></div>
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-36"></div>
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-20"></div>
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-32"></div>
          <div class="w-1/6 bg-neutral-200 dark:bg-neutral-800 rounded-t h-44"></div>
        </div>
      </div>
    } @else {
      <!-- Inline Bar -->
      <div
        class="animate-pulse bg-neutral-200 dark:bg-neutral-800 rounded-md"
        [style.height.px]="height"
        [style.width]="width"
      ></div>
    }
  `
})
export class LoadingSkeletonComponent {
  @Input() type: 'card' | 'row' | 'chart' | 'inline' = 'card';
  @Input() height = 24;
  @Input() width = '100%';
}
