import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LandingNavbarComponent } from './components/landing-navbar.component';
import { LandingHeroComponent } from './components/landing-hero.component';
import { LandingFeaturesComponent } from './components/landing-features.component';
import { LandingHowItWorksComponent } from './components/landing-how-it-works.component';
import { LandingSocialProofComponent } from './components/landing-social-proof.component';
import { LandingCtaComponent } from './components/landing-cta.component';
import { LandingFooterComponent } from './components/landing-footer.component';
import { CoinBackgroundComponent } from '../../shared/components/coin-background/coin-background.component';
import { SquirrelMascotComponent } from '../../shared/components/squirrel-mascot/squirrel-mascot.component';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [
    CommonModule,
    CoinBackgroundComponent,
    SquirrelMascotComponent,
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

      <!-- Squirrel Mascot (Guest Roaming Mode) -->
      @defer (on idle) {
        <app-squirrel-mascot initialAnchor="mid-right"></app-squirrel-mascot>
      }

      <!-- 7. Footer -->
      <app-landing-footer class="relative z-10"></app-landing-footer>
    </div>
  `
})
export class LandingComponent {}
