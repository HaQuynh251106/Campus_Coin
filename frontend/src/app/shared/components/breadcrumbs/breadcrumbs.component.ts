import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../icon/icon.component';

export interface BreadcrumbItem {
  label: string;
  url?: string;
}

@Component({
  selector: 'app-breadcrumbs',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <nav class="flex items-center gap-1.5 text-xs font-medium mb-4 text-neutral-500 dark:text-neutral-400" aria-label="Breadcrumb">
      <a
        routerLink="/app/home"
        class="hover:text-neutral-900 dark:hover:text-white transition-colors flex items-center gap-1"
      >
        <app-icon name="home" [size]="13" strokeWidth="1.5"></app-icon>
        <span>Campus</span>
      </a>

      @for (item of items; track item.label; let last = $last) {
        <app-icon name="chevron-right" [size]="11" strokeWidth="1.5" className="text-neutral-400 dark:text-neutral-600"></app-icon>

        @if (!last && item.url) {
          <a
            [routerLink]="item.url"
            class="hover:text-neutral-900 dark:hover:text-white transition-colors"
          >
            {{ item.label }}
          </a>
        } @else {
          <span class="text-neutral-900 dark:text-neutral-100 font-semibold px-1.5 py-0.5 rounded bg-neutral-100 dark:bg-neutral-800 text-[11px]">
            {{ item.label }}
          </span>
        }
      }
    </nav>
  `
})
export class BreadcrumbsComponent {
  @Input() items: BreadcrumbItem[] = [];
}
