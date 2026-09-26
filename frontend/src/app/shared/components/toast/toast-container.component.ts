import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ToastService, ToastItem } from '../../../core/services/toast.service';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-toast-container',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <!-- 1. Toast Notification Stacks (Top Right) -->
    <div
      class="fixed top-4 right-4 z-50 flex flex-col gap-2 max-w-sm w-full pointer-events-none px-4 sm:px-0"
      aria-live="polite"
    >
      @for (toast of toastService.toasts(); track toast.id) {
        <div
          class="pointer-events-auto rounded-xl p-3.5 border-2 shadow-lg flex items-start gap-3 transition-all duration-200 animate-slide-in backdrop-blur-md"
          [ngClass]="{
            'bg-emerald-50/95 dark:bg-emerald-950/90 border-emerald-600 text-emerald-950 dark:text-emerald-100': toast.type === 'success',
            'bg-rose-50/95 dark:bg-rose-950/90 border-rose-600 text-rose-950 dark:text-rose-100': toast.type === 'error',
            'bg-amber-50/95 dark:bg-amber-950/90 border-amber-500 text-amber-950 dark:text-amber-100': toast.type === 'warning',
            'bg-sky-50/95 dark:bg-sky-950/90 border-sky-500 text-sky-950 dark:text-sky-100': toast.type === 'info'
          }"
        >
          <!-- Icon -->
          <div class="shrink-0 mt-0.5">
            @if (toast.type === 'success') {
              <app-icon name="check-circle" size="18" className="text-emerald-600 dark:text-emerald-400"></app-icon>
            } @else if (toast.type === 'error') {
              <app-icon name="alert-triangle" size="18" className="text-rose-600 dark:text-rose-400"></app-icon>
            } @else if (toast.type === 'warning') {
              <app-icon name="alert-triangle" size="18" className="text-amber-600 dark:text-amber-400"></app-icon>
            } @else {
              <app-icon name="sparkles" size="18" className="text-sky-600 dark:text-sky-400"></app-icon>
            }
          </div>

          <!-- Content -->
          <div class="flex-1 min-w-0">
            @if (toast.title) {
              <p class="font-bold text-xs uppercase tracking-wider mb-0.5">
                {{ toast.title }}
              </p>
            }
            <p class="text-xs leading-relaxed font-medium break-words">
              {{ toast.message }}
            </p>
          </div>

          <!-- Close button -->
          <button
            type="button"
            (click)="toastService.dismiss(toast.id)"
            class="shrink-0 text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 p-0.5 transition-colors cursor-pointer"
            aria-label="Close notification"
          >
            <app-icon name="x" size="14"></app-icon>
          </button>
        </div>
      }
    </div>

    <!-- 2. In-App Confirmation Modal (Replaces window.confirm) -->
    @if (toastService.activeConfirm(); as confirm) {
      <div class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs animate-fade-in">
        <div
          class="bg-white dark:bg-neutral-900 border-2 border-neutral-900 dark:border-neutral-700 rounded-2xl shadow-2xl max-w-md w-full p-5 sm:p-6 animate-scale-up"
          role="dialog"
          aria-modal="true"
        >
          <div class="flex items-start gap-3.5 mb-4">
            <div
              class="w-10 h-10 rounded-xl flex items-center justify-center shrink-0 border"
              [ngClass]="confirm.danger ? 'bg-rose-100 dark:bg-rose-950/60 border-rose-300 dark:border-rose-800 text-rose-600 dark:text-rose-400' : 'bg-amber-100 dark:bg-amber-950/60 border-amber-300 dark:border-amber-800 text-amber-600 dark:text-amber-400'"
            >
              <app-icon name="alert-triangle" size="20"></app-icon>
            </div>
            <div>
              <h3 class="text-base font-bold text-neutral-900 dark:text-white">
                {{ confirm.title }}
              </h3>
              <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 mt-1 leading-relaxed">
                {{ confirm.message }}
              </p>
            </div>
          </div>

          <div class="flex items-center justify-end gap-2.5 pt-3 border-t border-neutral-200 dark:border-neutral-800">
            <button
              type="button"
              (click)="confirm.resolve(false)"
              class="px-4 py-2 rounded-xl border border-neutral-300 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-100 dark:hover:bg-neutral-800 text-xs font-semibold transition-colors cursor-pointer"
            >
              {{ confirm.cancelText || 'Cancel' }}
            </button>
            <button
              type="button"
              (click)="confirm.resolve(true)"
              class="px-4 py-2 rounded-xl text-xs font-bold transition-all shadow-xs cursor-pointer"
              [ngClass]="confirm.danger ? 'bg-rose-600 hover:bg-rose-700 text-white' : 'bg-amber-500 hover:bg-amber-600 text-neutral-950'"
            >
              {{ confirm.confirmText || 'Confirm' }}
            </button>
          </div>
        </div>
      </div>
    }
  `
})
export class ToastContainerComponent {
  readonly toastService = inject(ToastService);
}
