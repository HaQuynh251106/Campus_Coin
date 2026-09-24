import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { NavItem, STUDENT_NAV_ITEMS } from '../../navigation.config';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-nav-bottom',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <nav class="fixed bottom-0 left-0 right-0 z-40 bg-white/95 dark:bg-neutral-900/95 backdrop-blur-md border-t border-neutral-200 dark:border-neutral-800 shadow-sm pb-safe" aria-label="Mobile Navigation">
      <div class="flex items-center justify-around px-2 py-1.5 max-w-md mx-auto relative">
        @for (item of navItems; track item.route) {
          @if (item.isEmphasized) {
            <!-- Center Prominent Quick Add Button -->
            <a
              [routerLink]="item.route"
              class="relative -top-4 flex flex-col items-center group"
              aria-label="Quick Add Transaction"
            >
              <div class="w-11 h-11 rounded-full bg-amber-500 hover:bg-amber-600 shadow-md flex items-center justify-center text-neutral-950 transition-all">
                <app-icon name="plus-simple" [size]="20" strokeWidth="2"></app-icon>
              </div>
              <span class="text-[10px] font-medium mt-0.5 text-neutral-800 dark:text-neutral-200">
                {{ item.label }}
              </span>
            </a>
          } @else {
            <!-- Standard Nav Tab -->
            <a
              [routerLink]="item.route"
              routerLinkActive="text-amber-600 dark:text-amber-400 font-semibold"
              [routerLinkActiveOptions]="{ exact: false }"
              class="flex flex-col items-center justify-center py-1.5 px-3 rounded-lg text-neutral-500 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 transition-colors min-w-[56px]"
            >
              <app-icon [name]="item.icon" [size]="18" strokeWidth="1.5"></app-icon>
              <span class="text-[10px] font-medium mt-1">
                {{ item.label }}
              </span>
            </a>
          }
        }
      </div>
    </nav>
  `
})
export class NavBottomComponent {
  @Input() navItems: NavItem[] = STUDENT_NAV_ITEMS;
}
