import { Component, OnInit, OnDestroy, signal, inject, PLATFORM_ID } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { NavSidebarComponent } from '../../shared/components/nav-sidebar/nav-sidebar.component';
import { NavBottomComponent } from '../../shared/components/nav-bottom/nav-bottom.component';
import { TopBarComponent } from '../../shared/components/top-bar/top-bar.component';
import { ChatbotWidgetComponent } from '../../shared/components/chatbot-widget/chatbot-widget.component';
import { STUDENT_NAV_ITEMS, STUDENT_SIDEBAR_EXTRA_ITEMS } from '../../shared/navigation.config';

@Component({
  selector: 'app-student-layout',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    NavSidebarComponent,
    NavBottomComponent,
    TopBarComponent,
    ChatbotWidgetComponent
  ],
  template: `
    <div class="min-h-screen bg-brand-bg dark:bg-neutral-950 flex transition-colors">
      <!-- Desktop Sidebar (>= 1024px) driven reactively -->
      @if (isDesktop()) {
        <app-nav-sidebar
          [isAdmin]="false"
          [mainItems]="studentNavItems"
          [extraItems]="studentSidebarExtra"
        ></app-nav-sidebar>
      }

      <!-- Main App Content Area -->
      <div class="flex-1 flex flex-col min-w-0">
        <!-- Sticky Top Bar Header -->
        <app-top-bar></app-top-bar>

        <!-- Dynamic Routed Feature Views with safe bottom clearance for mascot launcher (B.7) -->
        <main class="flex-1 p-4 md:p-8 max-w-7xl w-full mx-auto pb-28 md:pb-32" [class.pb-36]="!isDesktop()">
          <router-outlet></router-outlet>
        </main>

        <!-- Floating AI Chatbot Assistant Widget with Mascot (Deferred on idle) -->
        @defer (on idle) {
          <app-chatbot-widget></app-chatbot-widget>
        }

        <!-- Mobile / Small Tablet Bottom Navigation Bar (< 1024px) -->
        @if (!isDesktop()) {
          <app-nav-bottom [navItems]="studentNavItems"></app-nav-bottom>
        }
      </div>
    </div>
  `
})
export class StudentLayoutComponent implements OnInit, OnDestroy {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  readonly isDesktop = signal<boolean>(true);
  private mediaQueryListener?: (e: MediaQueryListEvent) => void;
  private mql?: MediaQueryList;

  readonly studentNavItems = STUDENT_NAV_ITEMS;
  readonly studentSidebarExtra = STUDENT_SIDEBAR_EXTRA_ITEMS;

  ngOnInit(): void {
    if (this.isBrowser) {
      this.mql = window.matchMedia('(min-width: 1024px)');
      this.isDesktop.set(this.mql.matches);

      this.mediaQueryListener = (e: MediaQueryListEvent) => {
        this.isDesktop.set(e.matches);
      };

      if (this.mql.addEventListener) {
        this.mql.addEventListener('change', this.mediaQueryListener);
      } else {
        // Fallback for older browsers
        (this.mql as any).addListener(this.mediaQueryListener);
      }
    }
  }

  ngOnDestroy(): void {
    if (this.mql && this.mediaQueryListener) {
      if (this.mql.removeEventListener) {
        this.mql.removeEventListener('change', this.mediaQueryListener);
      } else {
        (this.mql as any).removeListener(this.mediaQueryListener);
      }
    }
  }
}
