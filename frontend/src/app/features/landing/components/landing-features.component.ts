import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface FeatureCard {
  icon: string;
  badge: string;
  title: string;
  description: string;
  accent: string;
}

@Component({
  selector: 'app-landing-features',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <section id="features" class="py-16 sm:py-24 border-t border-neutral-200/80 dark:border-neutral-800/80 bg-neutral-50/50 dark:bg-neutral-900/30">
      <div class="max-w-6xl mx-auto px-4 sm:px-6">

        <!-- Section Header -->
        <div class="text-center max-w-2xl mx-auto mb-12 sm:mb-16 space-y-3">
          <span class="text-xs font-semibold uppercase tracking-wider text-amber-600 dark:text-amber-400 block">
            Key Pillars
          </span>
          <h2 class="text-3xl sm:text-4xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
            Engineered for the reality of student life
          </h2>
          <p class="text-sm sm:text-base text-neutral-600 dark:text-neutral-300">
            Everything you need to keep your allowance on track, without the complexity of traditional banking software.
          </p>
        </div>

        <!-- 4-Card Responsive Grid -->
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
          @for (card of features; track card.title) {
            <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs hover:border-neutral-300 dark:hover:border-neutral-700 transition-all flex flex-col justify-between group">
              <div>
                <!-- Icon Header -->
                <div class="w-10 h-10 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 flex items-center justify-center mb-4 group-hover:scale-105 transition-transform">
                  <app-icon [name]="card.icon" [size]="20" strokeWidth="1.5"></app-icon>
                </div>

                <!-- Sub-badge -->
                <span class="text-[10px] font-semibold uppercase tracking-wider text-amber-600 dark:text-amber-400 block mb-1">
                  {{ card.badge }}
                </span>

                <!-- Title -->
                <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 mb-2 tracking-tight">
                  {{ card.title }}
                </h3>

                <!-- Description -->
                <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 leading-relaxed font-normal">
                  {{ card.description }}
                </p>
              </div>

              <!-- Bottom subtle indicator -->
              <div class="pt-4 mt-4 border-t border-neutral-100 dark:border-neutral-800/80 flex items-center gap-1 text-[11px] font-medium text-amber-600 dark:text-amber-400">
                <span>Included in free tier</span>
                <span>✓</span>
              </div>
            </div>
          }
        </div>

      </div>
    </section>
  `
})
export class LandingFeaturesComponent {
  features: FeatureCard[] = [
    {
      icon: 'zap',
      badge: 'Conversational Input',
      title: 'Fast Logging',
      description: 'Log campus spending on the fly with conversational natural-language quick-add, or bulk-import bank CSVs.',
      accent: 'amber'
    },
    {
      icon: 'sparkles',
      badge: 'Smart Auto-Tagging',
      title: 'AI Categorization',
      description: 'Auto-suggests the right category as you type your receipt or meal run, always fully editable and transparent.',
      accent: 'amber'
    },
    {
      icon: 'bell',
      badge: 'Category Caps',
      title: 'Budgets & Alerts',
      description: 'Set a monthly cap per category and get notified before you overspend with proactive 80% and over-limit warnings.',
      accent: 'amber'
    },
    {
      icon: 'trending-up',
      badge: 'Narrative Coach',
      title: 'Monthly Insights',
      description: 'Receive plain-language summaries of spending patterns, velocity changes, and an actionable saving tip each month.',
      accent: 'amber'
    }
  ];
}
