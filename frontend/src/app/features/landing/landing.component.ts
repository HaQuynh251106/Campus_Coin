import { Component, OnInit, OnDestroy, inject, PLATFORM_ID, signal } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { LandingNavbarComponent } from './components/landing-navbar.component';
import { LandingHeroComponent } from './components/landing-hero.component';
import { LandingFeaturesComponent } from './components/landing-features.component';
import { LandingHowItWorksComponent } from './components/landing-how-it-works.component';
import { LandingSocialProofComponent } from './components/landing-social-proof.component';
import { LandingCtaComponent } from './components/landing-cta.component';
import { LandingFooterComponent } from './components/landing-footer.component';
import { CoinBackgroundComponent } from '../../shared/components/coin-background/coin-background.component';
import { ChatbotWidgetComponent } from '../../shared/components/chatbot-widget/chatbot-widget.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [
    CommonModule,
    CoinBackgroundComponent,
    ChatbotWidgetComponent,
    IconComponent,
    LandingNavbarComponent,
    LandingHeroComponent,
    LandingFeaturesComponent,
    LandingHowItWorksComponent,
    LandingSocialProofComponent,
    LandingCtaComponent,
    LandingFooterComponent
  ],
  template: `
    <div class="relative min-h-screen flex flex-col bg-white dark:bg-neutral-950 text-neutral-900 dark:text-neutral-50 selection:bg-amber-500 selection:text-neutral-950 font-sans transition-colors overflow-hidden">
      <!-- 0. 3D Falling Coins Canvas Background (Deferred on idle to prioritize LCP) -->
      @defer (on idle) {
        <app-coin-background></app-coin-background>
      }

      <!-- 1. Top Navbar -->
      <app-landing-navbar class="relative z-10"></app-landing-navbar>

      <!-- Main Marketing Content -->
      <main class="flex-1 relative z-10">
        <!-- 2. Hero Section -->
        <app-landing-hero></app-landing-hero>

        <!-- 3. Feature Highlights (4 Pillars) -->
        <app-landing-features></app-landing-features>

        <!-- 4. How It Works (3 Steps) -->
        <app-landing-how-it-works></app-landing-how-it-works>

        <!-- 5. Social Proof / Student Stories -->
        <app-landing-social-proof></app-landing-social-proof>

        <!-- 6. Final CTA Banner -->
        <app-landing-cta></app-landing-cta>
      </main>

      <!-- Full Chatbot Assistant Widget with Mascot (Guest Roaming Mode with Gated Login Panel) -->
      @defer (on idle) {
        <app-chatbot-widget initialAnchor="mid-right" [isGuestMode]="true"></app-chatbot-widget>
      }

      <!-- 1. Floating Back-to-Top Button (Fixed Bottom-Left) -->
      @if (showBackToTop()) {
        <button
          type="button"
          (click)="scrollToTop()"
          class="fixed bottom-6 left-6 z-40 p-3 rounded-full bg-white/95 dark:bg-neutral-900/95 backdrop-blur-md border border-neutral-200 dark:border-neutral-800 shadow-subtle-md hover:shadow-subtle-lg text-neutral-600 dark:text-neutral-300 hover:text-amber-600 dark:hover:text-amber-400 hover:border-amber-500/30 transition-all duration-300 flex items-center justify-center cursor-pointer animate-fade-in group active:scale-95"
          title="Scroll back to top"
          aria-label="Scroll back to top"
        >
          <app-icon name="arrow-up" [size]="18" strokeWidth="2" className="group-hover:-translate-y-0.5 transition-transform"></app-icon>
        </button>
      }

      <!-- 7. Footer -->
      <app-landing-footer class="relative z-10"></app-landing-footer>
    </div>
  `
})
export class LandingComponent implements OnInit, OnDestroy {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  readonly showBackToTop = signal(false);
  private scrollListener?: () => void;

  ngOnInit(): void {
    if (this.isBrowser) {
      this.scrollListener = () => {
        const scrolled = window.scrollY > 350;
        if (this.showBackToTop() !== scrolled) {
          this.showBackToTop.set(scrolled);
        }
      };
      window.addEventListener('scroll', this.scrollListener, { passive: true });
    }
  }

  scrollToTop(): void {
    if (this.isBrowser) {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  ngOnDestroy(): void {
    if (this.isBrowser && this.scrollListener) {
      window.removeEventListener('scroll', this.scrollListener);
    }
  }
}
