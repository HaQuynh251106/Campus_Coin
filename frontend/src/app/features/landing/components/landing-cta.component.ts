import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-landing-cta',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <section class="py-16 sm:py-20 border-t border-neutral-200/80 dark:border-neutral-800/80 bg-white/60 dark:bg-neutral-950/60 backdrop-blur-xs">
      <div class="max-w-6xl mx-auto px-4 sm:px-6">

        <div class="relative overflow-hidden rounded-2xl bg-neutral-900 text-white border border-neutral-800 p-8 sm:p-12 text-center space-y-6 shadow-subtle-lg">
          <!-- Ambient Gold Glow Inside Banner -->
          <div class="absolute -right-20 -top-20 w-80 h-80 bg-amber-500/10 rounded-full blur-3xl pointer-events-none"></div>
          <div class="absolute -left-20 -bottom-20 w-80 h-80 bg-amber-500/10 rounded-full blur-3xl pointer-events-none"></div>

          <div class="relative z-10 max-w-2xl mx-auto space-y-4">
            <span class="inline-flex items-center text-xs font-semibold px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30 uppercase tracking-wider">
              Free Forever for Students
            </span>

            <h2 class="text-3xl sm:text-4xl font-bold tracking-tight text-white">
              Take control of your campus cash flow today.
            </h2>

            <p class="text-sm sm:text-base text-neutral-300 leading-relaxed font-normal">
              No credit card, no bank linking, and zero surprise fees. Track expenses, balance your allowance, and build lasting financial habits.
            </p>

            <div class="pt-4 flex flex-wrap items-center justify-center gap-4">
              <a
                routerLink="/auth/register"
                class="px-6 py-3 bg-amber-500 hover:bg-amber-400 active:scale-[0.98] text-neutral-950 font-semibold text-sm rounded-lg shadow-xs transition-all flex items-center gap-2 cursor-pointer"
              >
                <span>Sign up free</span>
                <app-icon name="arrow-right" [size]="16" strokeWidth="2"></app-icon>
              </a>

              <a
                routerLink="/auth/login"
                class="px-5 py-3 bg-neutral-800 hover:bg-neutral-750 text-neutral-200 border border-neutral-700 font-medium text-sm rounded-lg transition-colors cursor-pointer"
              >
                Log in to student portal
              </a>
            </div>
          </div>
        </div>

      </div>
    </section>
  `
})
export class LandingCtaComponent {}
