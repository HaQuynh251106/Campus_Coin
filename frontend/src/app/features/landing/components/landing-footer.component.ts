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
      <!-- Clear Glass Savings Jar Receptacle (Visual Anchor where falling coins land) -->
      <div class="relative -top-12 flex flex-col items-center justify-center mx-auto mb-[-24px] pointer-events-auto select-none">
        <div class="relative flex flex-col items-center group">
          <!-- Floating "+1 Coin" text feedback items on caught coins -->
          @for (item of floatingCoins(); track item.id) {
            <span
              class="absolute -top-7 font-mono font-bold text-xs text-amber-600 dark:text-amber-400 drop-shadow-sm pointer-events-none animate-float-fade z-20"
              [style.transform]="'translateX(' + item.xOffset + 'px)'"
            >
              {{ item.text }}
            </span>
          }

          <!-- Clear Glass Savings Jar SVG Illustration -->
          <div class="w-18 h-22 sm:w-20 sm:h-24 relative flex items-center justify-center drop-shadow-md">
            <svg viewBox="0 0 48 54" class="w-full h-full overflow-visible">
              <defs>
                <!-- Glass Body Tint Gradient -->
                <linearGradient id="masonGlassGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#F59E0B" stop-opacity="0.12" />
                  <stop offset="50%" stop-color="#FFFFFF" stop-opacity="0.08" />
                  <stop offset="100%" stop-color="#D97706" stop-opacity="0.06" />
                </linearGradient>
                <!-- Lid Metal Gradient -->
                <linearGradient id="masonLidMetal" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#E2E8F0" />
                  <stop offset="50%" stop-color="#CBD5E1" />
                  <stop offset="100%" stop-color="#94A3B8" />
                </linearGradient>
                <!-- Gold Coin Gradient -->
                <linearGradient id="masonCoinGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#FDE047" />
                  <stop offset="100%" stop-color="#EAB308" />
                </linearGradient>
              </defs>

              <!-- 1. Jar Mouth & Threaded Metal Lid -->
              <rect x="14" y="6" width="20" height="4" rx="1.5" fill="url(#masonLidMetal)" stroke="#64748B" stroke-width="0.8" />
              <!-- Lid Grip Ridges -->
              <line x1="17" y1="7" x2="17" y2="9.5" stroke="#94A3B8" stroke-width="0.75" />
              <line x1="21" y1="7" x2="21" y2="9.5" stroke="#94A3B8" stroke-width="0.75" />
              <line x1="24" y1="7" x2="24" y2="9.5" stroke="#94A3B8" stroke-width="0.75" />
              <line x1="27" y1="7" x2="27" y2="9.5" stroke="#94A3B8" stroke-width="0.75" />
              <line x1="31" y1="7" x2="31" y2="9.5" stroke="#94A3B8" stroke-width="0.75" />
              <!-- Glass Neck Ring -->
              <rect x="15" y="10" width="18" height="3" rx="0.5" fill="url(#masonGlassGrad)" stroke="#D97706" stroke-width="0.9" />

              <!-- 2. Transparent Glass Cylindrical Jar Body -->
              <path
                d="M 16 13 C 12 14, 9 17, 9 20 L 9 46 C 9 49.5, 12 51, 16 51 L 32 51 C 36 51, 39 49.5, 39 46 L 39 20 C 39 17, 36 14, 32 13 Z"
                fill="url(#masonGlassGrad)"
                stroke="#D97706"
                stroke-width="1.4"
                class="dark:stroke-amber-500"
              />

              <!-- 3. Glass Specular Reflections (Left Wall, Shoulder, Right Wall) -->
              <!-- Left wall vertical glass shine streak -->
              <path
                d="M 12 21 L 12 45"
                fill="none"
                stroke="rgba(255, 255, 255, 0.85)"
                stroke-width="1.3"
                stroke-linecap="round"
                class="dark:stroke-white/40"
              />
              <!-- Top left shoulder reflection -->
              <path
                d="M 14 18 Q 18 15 22 15"
                fill="none"
                stroke="rgba(255, 255, 255, 0.65)"
                stroke-width="1"
                stroke-linecap="round"
                class="dark:stroke-white/35"
              />
              <!-- Right edge soft reflection -->
              <path
                d="M 36 23 L 36 43"
                fill="none"
                stroke="rgba(255, 255, 255, 0.3)"
                stroke-width="1"
                stroke-linecap="round"
              />

              <!-- 4. Piled Gold Coins Visible Inside the Clear Glass Base -->
              <!-- Bottom coin base row -->
              <ellipse cx="18" cy="46" rx="6" ry="2.2" fill="url(#masonCoinGrad)" stroke="#B45309" stroke-width="0.8" />
              <ellipse cx="30" cy="46" rx="6" ry="2.2" fill="url(#masonCoinGrad)" stroke="#B45309" stroke-width="0.8" />
              <!-- Middle coin row -->
              <ellipse cx="24" cy="43.5" rx="6.5" ry="2.3" fill="url(#masonCoinGrad)" stroke="#B45309" stroke-width="0.8" />
              <!-- Angled top resting coins -->
              <ellipse cx="17.5" cy="40.5" rx="5.2" ry="2" fill="url(#masonCoinGrad)" stroke="#B45309" stroke-width="0.8" transform="rotate(-10 17.5 40.5)" />
              <ellipse cx="29.5" cy="39.5" rx="5.2" ry="2" fill="url(#masonCoinGrad)" stroke="#B45309" stroke-width="0.8" transform="rotate(12 29.5 39.5)" />

              <!-- 5. Classic "SAVINGS" Label on Front Glass -->
              <rect
                x="15"
                y="23"
                width="18"
                height="9"
                rx="1.5"
                fill="#FFFBEB"
                stroke="#F59E0B"
                stroke-width="0.8"
                class="dark:fill-neutral-800 dark:stroke-amber-600"
              />
              <text
                x="24"
                y="29.5"
                font-size="4.2"
                font-weight="800"
                font-family="system-ui, sans-serif"
                text-anchor="middle"
                fill="#B45309"
                letter-spacing="0.5"
                class="dark:fill-amber-400"
              >SAVINGS</text>
            </svg>
          </div>
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

  readonly floatingCoins = this.savingsJarService.floatingCoins;
}
