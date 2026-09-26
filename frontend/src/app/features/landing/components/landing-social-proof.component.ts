import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface StudentQuote {
  quote: string;
  name: string;
  major: string;
  avatar: string;
}

@Component({
  selector: 'app-landing-social-proof',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section id="stories" class="py-16 sm:py-24 border-t border-neutral-200/80 dark:border-neutral-800/80 bg-neutral-50/50 dark:bg-neutral-900/30">
      <div class="max-w-6xl mx-auto px-4 sm:px-6">

        <!-- Section Header -->
        <div class="text-center max-w-2xl mx-auto mb-12 sm:mb-16 space-y-3">
          <span class="text-xs font-semibold uppercase tracking-wider text-amber-600 dark:text-amber-400 block">
            Student Perspective
          </span>
          <h2 class="text-3xl sm:text-4xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
            Real campus scenarios, zero financial stress
          </h2>
          <p class="text-sm sm:text-base text-neutral-600 dark:text-neutral-300">
            Built for student life — whether managing monthly family allowances, lab stipends, or campus retail shifts.
          </p>
        </div>

        <!-- 3 Fictional Student Quote Cards -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
          @for (item of quotes; track item.name) {
            <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs flex flex-col justify-between">
              <!-- Quote text -->
              <p class="text-xs sm:text-sm text-neutral-700 dark:text-neutral-200 leading-relaxed italic mb-6">
                "{{ item.quote }}"
              </p>

              <!-- Persona Footer -->
              <div class="flex items-center gap-3 pt-4 border-t border-neutral-100 dark:border-neutral-800">
                <div class="w-10 h-10 rounded-full overflow-hidden bg-neutral-100 dark:bg-neutral-800 shrink-0 border border-neutral-200 dark:border-neutral-700">
                  <img
                    [src]="item.avatar"
                    [alt]="item.name"
                    width="40"
                    height="40"
                    loading="lazy"
                    class="w-full h-full object-cover"
                  />
                </div>
                <div>
                  <h4 class="font-semibold text-sm text-neutral-900 dark:text-neutral-100">
                    {{ item.name }}
                  </h4>
                  <p class="text-xs text-[var(--color-text-muted)]">
                    {{ item.major }}
                  </p>
                </div>
              </div>
            </div>
          }
        </div>

        <!-- Context Strip -->
        <div class="mt-12 p-4 rounded-xl bg-amber-500/5 dark:bg-amber-500/10 border border-amber-500/20 flex flex-wrap items-center justify-between gap-4 text-xs font-medium text-amber-900 dark:text-amber-200">
          <div class="flex items-center gap-2">
            <span>🎓</span>
            <span>Designed for every kind of student income — allowances, part-time work, scholarships, and freelance tutoring.</span>
          </div>
          <span class="text-amber-700 dark:text-amber-400 font-semibold">100% Free for Students</span>
        </div>

      </div>
    </section>
  `
})
export class LandingSocialProofComponent {
  quotes: StudentQuote[] = [
    {
      quote: "Splitting dorm Wi-Fi, lab equipment, and food cart meals used to drain my account before midterms. Campus Coin's AI categories showed me exactly where to cut back.",
      name: "Minh T.",
      major: "Junior, Computer Science",
      avatar: "https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?auto=format&fit=crop&w=128&q=80"
    },
    {
      quote: "The fact that I don't have to connect my actual bank account was huge for me. I just type my coffee and textbook expenses and the budget alerts keep me honest.",
      name: "Elena R.",
      major: "Sophomore, Biology",
      avatar: "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=128&q=80"
    },
    {
      quote: "The monthly insight summaries read like advice from an older sibling. It helped me save $400 over the summer for my thesis materials and lab supplies.",
      name: "Marcus K.",
      major: "Senior, Economics",
      avatar: "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=128&q=80"
    }
  ];
}
