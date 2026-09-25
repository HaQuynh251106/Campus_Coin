import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../shared/components/icon/icon.component';

interface StepItem {
  number: string;
  icon: string;
  title: string;
  description: string;
}

@Component({
  selector: 'app-landing-how-it-works',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <section id="how-it-works" class="py-16 sm:py-24 border-t border-neutral-200/80 dark:border-neutral-800/80 bg-white/60 dark:bg-neutral-950/60 backdrop-blur-xs">
      <div class="max-w-6xl mx-auto px-4 sm:px-6">

        <!-- Section Header -->
        <div class="text-center max-w-2xl mx-auto mb-12 sm:mb-16 space-y-3">
          <span class="inline-flex items-center text-xs font-semibold px-2.5 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20 uppercase tracking-wider">
            Simple Workflow
          </span>
          <h2 class="text-3xl sm:text-4xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
            How Campus Coin Works
          </h2>
          <p class="text-sm sm:text-base text-neutral-600 dark:text-neutral-300">
            Get complete clarity on your student budget in three effortless steps.
          </p>
        </div>

        <!-- 3-Step Horizontal / Vertical Sequence -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-8 relative">

          @for (step of steps; track step.number; let isLast = $last) {
            <div class="relative flex flex-col items-center text-center p-6 rounded-xl bg-neutral-50/50 dark:bg-neutral-900/40 border border-neutral-200/80 dark:border-neutral-800">

              <!-- Step Number & Icon Badge -->
              <div class="flex items-center gap-2 mb-4">
                <div class="w-10 h-10 rounded-xl bg-amber-500/10 dark:bg-amber-500/15 border border-amber-500/25 text-amber-700 dark:text-amber-400 font-bold text-sm flex items-center justify-center shadow-xs">
                  {{ step.number }}
                </div>
                <div class="w-10 h-10 rounded-xl bg-neutral-100 dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 text-neutral-600 dark:text-neutral-300 flex items-center justify-center shadow-xs">
                  <app-icon [name]="step.icon" [size]="18" strokeWidth="1.5"></app-icon>
                </div>
              </div>

              <!-- Title -->
              <h3 class="font-semibold text-lg text-neutral-900 dark:text-neutral-50 mb-2 tracking-tight">
                {{ step.title }}
              </h3>

              <!-- Description -->
              <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 leading-relaxed font-normal">
                {{ step.description }}
              </p>
            </div>
          }

        </div>

      </div>
    </section>
  `
})
export class LandingHowItWorksComponent {
  steps: StepItem[] = [
    {
      number: '01',
      icon: 'user',
      title: 'Sign up in seconds',
      description: 'Create your student account with your campus email. No bank linking, no credit checks, and no sensitive credentials.'
    },
    {
      number: '02',
      icon: 'plus-circle',
      title: 'Log your spending',
      description: 'Type natural sentences like "Coffee $4.50" or upload statement CSVs. AI categorizes your receipts automatically.'
    },
    {
      number: '03',
      icon: 'sparkles',
      title: 'Get personalized tips',
      description: 'Monitor live monthly budget progress and get plain-language saving advice tailored to your campus habits.'
    }
  ];
}
