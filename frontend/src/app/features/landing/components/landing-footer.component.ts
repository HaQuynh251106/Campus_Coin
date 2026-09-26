import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SavingsJarService } from '../../../core/services/savings-jar.service';

@Component({
  selector: 'app-landing-footer',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent],
  template: `
    <footer class="border-t border-neutral-200 dark:border-neutral-800 bg-neutral-50/80 dark:bg-neutral-900/70 backdrop-blur-xs transition-colors relative">
      <!-- Savings Jar Receptacle (Visual Anchor where falling coins land) -->
      <div class="relative -top-11 flex flex-col items-center justify-center mx-auto mb-[-24px] pointer-events-auto select-none">
        <div class="relative flex flex-col items-center group">
          <!-- Floating "+1 Coin" text feedback items -->
          @for (item of floatingCoins(); track item.id) {
            <span
              class="absolute -top-7 font-mono font-bold text-xs text-amber-600 dark:text-amber-400 pointer-events-none animate-float-fade z-20"
              [style.transform]="'translateX(' + item.xOffset + 'px)'"
            >
              {{ item.text }}
            </span>
          }

          <!-- Glass Acorn Savings Jar SVG Graphic -->
          <div class="w-16 h-20 sm:w-20 sm:h-24 relative flex items-center justify-center drop-shadow-md">
            <svg viewBox="0 0 48 56" class="w-full h-full overflow-visible">
              <defs>
                <linearGradient id="jarGlassGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#F59E0B" stop-opacity="0.14" />
                  <stop offset="100%" stop-color="#D97706" stop-opacity="0.05" />
                </linearGradient>
                <linearGradient id="jarCapWood" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#92400E" />
                  <stop offset="100%" stop-color="#78350F" />
                </linearGradient>
                <linearGradient id="jarGoldCoin" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#FDE047" />
                  <stop offset="100%" stop-color="#EAB308" />
                </linearGradient>
              </defs>

              <!-- 1. Acorn Cap Stem -->
              <path d="M 24 2 C 24 0, 26 0, 26 2 L 25 7 L 23 7 Z" fill="#78350F" />

              <!-- 2. Acorn Cap / Wooden Lid with Coin Slot -->
              <path
                d="M 9 16 C 9 7, 39 7, 39 16 C 39 18, 9 18, 9 16 Z"
                fill="url(#jarCapWood)"
                stroke="#451A03"
                stroke-width="1.2"
              />
              <!-- Cap texture cross-hatches -->
              <path d="M 15 10 L 19 16 M 21 8 L 27 16 M 29 9 L 33 16" stroke="#B45309" stroke-width="0.8" stroke-linecap="round" />
              <!-- Coin Slot on top of lid -->
              <rect x="20" y="8" width="8" height="2" rx="1" fill="#18181B" stroke="#451A03" stroke-width="0.5" />

              <!-- 3. Glass Acorn Jar Body (Transparent with amber tint) -->
              <path
                d="M 11 17 C 8 28, 13 44, 24 50 C 35 44, 40 28, 37 17 Z"
                fill="url(#jarGlassGrad)"
                stroke="#D97706"
                stroke-width="1.5"
                class="dark:stroke-amber-400"
              />

              <!-- Glass Left Curved Specular Highlight -->
              <path
                d="M 14 20 C 11 28, 14 38, 20 44"
                fill="none"
                stroke="rgba(255, 255, 255, 0.7)"
                stroke-width="1.2"
                stroke-linecap="round"
              />

              <!-- 4. Inside the jar: Settled gold coins -->
              <ellipse cx="22" cy="45" rx="5" ry="2.2" fill="url(#jarGoldCoin)" stroke="#CA8A04" stroke-width="0.8" />
              <ellipse cx="28" cy="43" rx="4.5" ry="2" fill="url(#jarGoldCoin)" stroke="#CA8A04" stroke-width="0.8" />
              @if (savedCount() > 0) {
                <!-- Additional coins stack up visually as counter increases -->
                <ellipse cx="24" cy="40" rx="5" ry="2.2" fill="url(#jarGoldCoin)" stroke="#CA8A04" stroke-width="0.8" />
              }
              @if (savedCount() >= 5) {
                <ellipse cx="20" cy="36" rx="4.5" ry="2" fill="url(#jarGoldCoin)" stroke="#CA8A04" stroke-width="0.8" />
                <ellipse cx="27" cy="35" rx="4.5" ry="2" fill="url(#jarGoldCoin)" stroke="#CA8A04" stroke-width="0.8" />
              }

              <!-- 5. Front Glass Brand Coin Seal -->
              <circle cx="24" cy="27" r="5.5" fill="url(#jarGoldCoin)" stroke="#B45309" stroke-width="0.9" />
              <!-- Lightning Bolt Icon inside coin seal -->
              <path d="M 24.5 24 L 22.5 27 L 24 27 L 23.5 30 L 25.5 26.8 L 24.2 26.8 Z" fill="#78350F" />
            </svg>
          </div>

          <!-- Running Counter: "Saved today: X coins" -->
          <div class="mt-2 inline-flex items-center gap-2 px-3 py-1 rounded-full bg-white dark:bg-neutral-800 border border-amber-500/30 text-xs font-medium text-neutral-800 dark:text-neutral-100 shadow-xs">
            <span class="w-2 h-2 rounded-full bg-amber-500 animate-pulse"></span>
            <span>
              Saved today: <strong class="font-bold text-amber-600 dark:text-amber-400 font-mono">{{ savedCount() }}</strong> {{ savedCount() === 1 ? 'coin' : 'coins' }}
            </span>
          </div>
          <span class="text-[10px] text-neutral-500 dark:text-neutral-400 mt-0.5 font-normal">
            Coins landing here automatically tucked into savings
          </span>
        </div>
      </div>

      <div class="max-w-6xl mx-auto px-4 sm:px-6 py-12">
        <div class="grid grid-cols-1 md:grid-cols-4 gap-8 pb-10 border-b border-neutral-200 dark:border-neutral-800">

          <!-- Brand Column -->
          <div class="md:col-span-2 space-y-3">
            <div class="flex items-center gap-2.5">
              <div class="w-8 h-8 rounded-lg bg-amber-500 flex items-center justify-center text-neutral-950 shadow-xs">
                <app-icon name="squirrel-logo" [size]="18" strokeWidth="1.75"></app-icon>
              </div>
              <span class="font-bold text-lg text-neutral-900 dark:text-neutral-50 tracking-tight">
                Campus Coin
              </span>
            </div>
            <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 max-w-sm leading-relaxed">
              Smart spending, student style. A modern financial tracking and AI categorization tool built specifically for college cohorts.
            </p>
          </div>

          <!-- Product Links -->
          <div class="space-y-2.5">
            <h4 class="text-xs font-semibold uppercase tracking-wider text-neutral-900 dark:text-neutral-100">
              Product
            </h4>
            <ul class="space-y-2 text-xs text-neutral-600 dark:text-neutral-300">
              <li>
                <a href="#features" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Features
                </a>
              </li>
              <li>
                <a href="#how-it-works" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  How It Works
                </a>
              </li>
              <li>
                <a href="#stories" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Student Stories
                </a>
              </li>
            </ul>
          </div>

          <!-- Account Links -->
          <div class="space-y-2.5">
            <h4 class="text-xs font-semibold uppercase tracking-wider text-neutral-900 dark:text-neutral-100">
              Account & Portals
            </h4>
            <ul class="space-y-2 text-xs text-neutral-600 dark:text-neutral-300">
              <li>
                <a routerLink="/auth/login" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Sign In
                </a>
              </li>
              <li>
                <a routerLink="/auth/register" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Create Account
                </a>
              </li>
              <li>
                <a routerLink="/auth/forgot-password" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Recover Password
                </a>
              </li>
              <li>
                <a routerLink="/auth/login" class="hover:text-amber-600 dark:hover:text-amber-400 transition-colors">
                  Institutional Admin
                </a>
              </li>
            </ul>
          </div>

        </div>

        <!-- Small print -->
        <div class="pt-8 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-[var(--color-text-muted)]">
          <p>
            &copy; 2026 Campus Coin. A student-centered financial wellness initiative.
          </p>
        </div>
      </div>
    </footer>
  `,
  styles: [`
    @keyframes floatFade {
      0% {
        opacity: 0;
        transform: translateY(0) scale(0.85);
      }
      20% {
        opacity: 1;
        transform: translateY(-8px) scale(1.1);
      }
      100% {
        opacity: 0;
        transform: translateY(-28px) scale(1);
      }
    }
    .animate-float-fade {
      animation: floatFade 1.05s ease-out forwards;
    }
  `]
})
export class LandingFooterComponent {
  private savingsJarService = inject(SavingsJarService);

  readonly savedCount = this.savingsJarService.savedCount;
  readonly floatingCoins = this.savingsJarService.floatingCoins;
}
