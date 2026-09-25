import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { CategoryIconComponent } from '../../../shared/components/category-icon/category-icon.component';

@Component({
  selector: 'app-landing-hero',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, CategoryIconComponent],
  template: `
    <section class="relative pt-12 pb-16 sm:pt-20 sm:pb-24 overflow-hidden">
      <!-- Ambient Background Glow (Subtle Brand Gold) -->
      <div class="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-amber-500/5 dark:bg-amber-500/10 rounded-full blur-3xl pointer-events-none"></div>

      <div class="max-w-6xl mx-auto px-4 sm:px-6 relative z-10">
        <div class="grid grid-cols-1 lg:grid-cols-12 gap-12 lg:gap-8 items-center">

          <!-- Left Column: Copy & Value Proposition (7 cols) -->
          <div class="lg:col-span-7 space-y-6 text-center lg:text-left">
            <!-- Pill Tag -->
            <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-amber-500/10 dark:bg-amber-500/15 border border-amber-500/20 text-amber-800 dark:text-amber-300 text-xs font-medium">
              <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse"></span>
              <span>Built for University Students &bull; Zero Bank Link Required</span>
            </div>

            <!-- Main Headline -->
            <h1 class="text-4xl sm:text-5xl lg:text-6xl font-bold tracking-tight text-neutral-950 dark:text-white leading-[1.1]">
              Smart campus spending.
              <span class="block text-amber-600 dark:text-amber-400">
                Effortless student budgeting.
              </span>
            </h1>

            <!-- Supporting Paragraph -->
            <p class="text-base sm:text-lg text-neutral-600 dark:text-neutral-300 max-w-xl mx-auto lg:mx-0 leading-relaxed font-normal">
              Take the stress out of your semester allowance. Log dining hall runs in seconds, get conversational AI expense tagging, and master your student cash flow without handing over banking passwords.
            </p>

            <!-- Call to Actions -->
            <div class="flex flex-wrap items-center justify-center lg:justify-start gap-3 pt-2">
              <a
                routerLink="/auth/register"
                class="px-6 py-3 bg-amber-500 hover:bg-amber-600 active:scale-[0.98] text-neutral-950 font-semibold text-sm rounded-lg shadow-xs transition-all flex items-center gap-2 cursor-pointer"
              >
                <span>Get started free</span>
                <app-icon name="arrow-right" [size]="16" strokeWidth="2"></app-icon>
              </a>

              <a
                href="#how-it-works"
                (click)="scrollToHowItWorks($event)"
                class="px-5 py-3 bg-white dark:bg-neutral-800/80 hover:bg-neutral-50 dark:hover:bg-neutral-800 text-neutral-800 dark:text-neutral-200 border border-neutral-300 dark:border-neutral-700 font-medium text-sm rounded-lg transition-colors cursor-pointer"
              >
                See how it works
              </a>
            </div>

            <!-- Instant Demo Quick Button -->
            <div class="pt-2 flex items-center justify-center lg:justify-start gap-2 text-xs text-[var(--color-text-muted)]">
              <span>Want to test drive immediately?</span>
              <button
                type="button"
                (click)="tryDemo()"
                class="text-amber-600 dark:text-amber-400 font-semibold hover:underline cursor-pointer flex items-center gap-1"
              >
                <span>Launch Interactive Demo</span>
                <span>⚡</span>
              </button>
            </div>

            <!-- Quick Trust Badges -->
            <div class="pt-4 flex flex-wrap items-center justify-center lg:justify-start gap-6 text-xs text-[var(--color-text-muted)] border-t border-neutral-200 dark:border-neutral-800/80">
              <div class="flex items-center gap-1.5">
                <app-icon name="shield-check" [size]="15" strokeWidth="1.5" className="text-emerald-500"></app-icon>
                <span>100% Client-Side Privacy</span>
              </div>
              <div class="flex items-center gap-1.5">
                <app-icon name="check-circle" [size]="15" strokeWidth="1.5" className="text-amber-500"></app-icon>
                <span>No Credit Card Needed</span>
              </div>
              <div class="flex items-center gap-1.5">
                <app-icon name="sparkles" [size]="15" strokeWidth="1.5" className="text-amber-500"></app-icon>
                <span>Instant AI Assistant</span>
              </div>
            </div>
          </div>

          <!-- Right Column: Interactive Real Angular Component Mockup Preview (5 cols) -->
          <div class="lg:col-span-5 relative">
            <!-- Decorative Border Highlight -->
            <div class="card-brutal p-5 sm:p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-2xl shadow-subtle-lg relative space-y-4 border-t-2 border-t-amber-500">

              <!-- Mock Header: Month & Net Balance -->
              <div class="flex items-center justify-between pb-3 border-b border-neutral-100 dark:border-neutral-800">
                <div>
                  <span class="text-[11px] font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded-full">
                    September 2026
                  </span>
                  <div class="text-2xl sm:text-3xl font-bold text-neutral-950 dark:text-white mt-1">
                    $423.50
                  </div>
                  <span class="text-[11px] text-[var(--color-text-muted)]">Net Campus Balance</span>
                </div>

                <div class="w-9 h-9 rounded-full bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-600 dark:text-amber-400 text-sm font-semibold">
                  SV
                </div>
              </div>

              <!-- 3-Metric Mini Strip -->
              <div class="grid grid-cols-3 gap-2">
                <div class="bg-neutral-50 dark:bg-neutral-800/50 p-2.5 rounded-lg border border-neutral-200/60 dark:border-neutral-800 text-center">
                  <span class="text-[10px] text-[var(--color-text-muted)] block">Income</span>
                  <span class="font-semibold text-xs text-emerald-600 dark:text-emerald-400">+$550.00</span>
                </div>
                <div class="bg-neutral-50 dark:bg-neutral-800/50 p-2.5 rounded-lg border border-neutral-200/60 dark:border-neutral-800 text-center">
                  <span class="text-[10px] text-[var(--color-text-muted)] block">Expenses</span>
                  <span class="font-semibold text-xs text-rose-600 dark:text-rose-400">-$126.50</span>
                </div>
                <div class="bg-neutral-50 dark:bg-neutral-800/50 p-2.5 rounded-lg border border-neutral-200/60 dark:border-neutral-800 text-center">
                  <span class="text-[10px] text-[var(--color-text-muted)] block">Savings</span>
                  <span class="font-semibold text-xs text-amber-600 dark:text-amber-400">77%</span>
                </div>
              </div>

              <!-- AI Advisor Snippet (Real free-licensed student avatar) -->
              <div class="p-3 bg-amber-500/5 dark:bg-amber-500/10 border border-amber-500/20 rounded-xl flex items-start gap-3">
                <div class="w-8 h-8 rounded-lg overflow-hidden shrink-0 border border-amber-500/30">
                  <img
                    src="https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=128&q=80"
                    alt="Campus AI Advisor"
                    width="32"
                    height="32"
                    class="w-full h-full object-cover"
                  />
                </div>
                <div class="min-w-0 flex-1">
                  <div class="flex items-center justify-between text-[11px] mb-0.5">
                    <span class="font-semibold text-neutral-900 dark:text-neutral-100">Campus AI Coach</span>
                    <span class="text-amber-600 dark:text-amber-400 font-medium">Smart Tip</span>
                  </div>
                  <p class="text-[11px] text-neutral-600 dark:text-neutral-300 leading-snug">
                    "Campus dining was your main cost this week. Packing lunch on Thursdays will save ~$48/month."
                  </p>
                </div>
              </div>

              <!-- Sample Transaction Rows Preview With Curated Soft Tags -->
              <div class="space-y-2 pt-1">
                <div class="flex items-center justify-between text-xs font-semibold text-neutral-700 dark:text-neutral-300 px-0.5">
                  <span>Recent Activity</span>
                  <span class="text-[10px] font-mono text-[var(--color-text-muted)]">Today</span>
                </div>

                <!-- Row 1: Food & Dining (Mobile-banking icon avatar + plain label) -->
                <div class="p-2.5 rounded-lg bg-neutral-50/70 dark:bg-neutral-800/40 border border-neutral-200/70 dark:border-neutral-800 flex items-center justify-between gap-3">
                  <div class="flex items-center gap-2.5 min-w-0">
                    <app-category-icon name="Food & Dining" icon="utensils" color="#EA580C" size="sm"></app-category-icon>
                    <div class="min-w-0">
                      <div class="text-xs font-medium text-neutral-900 dark:text-neutral-100 truncate">
                        Dining Hall Grilled Chicken
                      </div>
                      <div class="text-[11px] text-[var(--color-text-muted)]">Food & Dining</div>
                    </div>
                  </div>
                  <span class="font-mono text-xs font-semibold text-rose-600 dark:text-rose-400 whitespace-nowrap">
                    -$14.50
                  </span>
                </div>

                <!-- Row 2: Coffee & Snacks -->
                <div class="p-2.5 rounded-lg bg-neutral-50/70 dark:bg-neutral-800/40 border border-neutral-200/70 dark:border-neutral-800 flex items-center justify-between gap-3">
                  <div class="flex items-center gap-2.5 min-w-0">
                    <app-category-icon name="Coffee & Snacks" icon="coffee" color="#0D9488" size="sm"></app-category-icon>
                    <div class="min-w-0">
                      <div class="text-xs font-medium text-neutral-900 dark:text-neutral-100 truncate">
                        Library Cafe Iced Latte
                      </div>
                      <div class="text-[11px] text-[var(--color-text-muted)]">Coffee & Snacks</div>
                    </div>
                  </div>
                  <span class="font-mono text-xs font-semibold text-rose-600 dark:text-rose-400 whitespace-nowrap">
                    -$4.80
                  </span>
                </div>

                <!-- Row 3: Books & Supplies -->
                <div class="p-2.5 rounded-lg bg-neutral-50/70 dark:bg-neutral-800/40 border border-neutral-200/70 dark:border-neutral-800 flex items-center justify-between gap-3">
                  <div class="flex items-center gap-2.5 min-w-0">
                    <app-category-icon name="Books & Supplies" icon="book-open" color="#0EA5E9" size="sm"></app-category-icon>
                    <div class="min-w-0">
                      <div class="text-xs font-medium text-neutral-900 dark:text-neutral-100 truncate">
                        Calculus Lab Notebook
                      </div>
                      <div class="text-[11px] text-[var(--color-text-muted)]">Books & Supplies</div>
                    </div>
                  </div>
                  <span class="font-mono text-xs font-semibold text-rose-600 dark:text-rose-400 whitespace-nowrap">
                    -$22.00
                  </span>
                </div>
              </div>

            </div>
          </div>

        </div>
      </div>
    </section>
  `
})
export class LandingHeroComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  scrollToHowItWorks(event: Event): void {
    event.preventDefault();
    const el = document.getElementById('how-it-works');
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  tryDemo(): void {
    this.auth.loginAsDemo().subscribe(() => {
      this.router.navigate(['/app/home']);
    });
  }
}
