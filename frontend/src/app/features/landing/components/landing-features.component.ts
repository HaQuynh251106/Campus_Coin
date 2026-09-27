import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../../shared/components/icon/icon.component';

export interface AiFeatureItem {
  id: string;
  step: string;
  icon: string;
  badge: string;
  title: string;
  description: string;
  keyBenefit: string;
}

@Component({
  selector: 'app-landing-features',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <section id="features" class="py-16 sm:py-24 border-t border-neutral-200/80 dark:border-neutral-800/80 bg-neutral-50/50 dark:bg-neutral-900/30 transition-colors">
      <div class="max-w-5xl mx-auto px-4 sm:px-6">

        <!-- Section Header -->
        <div class="text-center max-w-3xl mx-auto mb-14 sm:mb-20 space-y-4">
          <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-semibold bg-amber-500/10 border border-amber-500/20 text-amber-700 dark:text-amber-400">
            <app-icon name="sparkles" [size]="14"></app-icon>
            <span>AI-Powered Financial Assistant</span>
          </div>

          <h2 class="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
            Smart financial intelligence, built for student life
          </h2>

          <p class="text-sm sm:text-base text-neutral-600 dark:text-neutral-300 max-w-2xl mx-auto leading-relaxed">
            Campus Coin pairs deep privacy safeguards with a full suite of AI capabilities — designed to help you stay on budget, spot saving opportunities, and manage your money without the complexity of traditional banking software.
          </p>
        </div>

        <!-- Vertically Stacked List of 7 AI Capabilities -->
        <div class="space-y-6">
          @for (item of aiFeatures; track item.id; let idx = $index) {
            <article class="p-6 sm:p-8 rounded-2xl bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 shadow-subtle hover:border-amber-500/30 transition-all duration-300 relative group overflow-hidden">
              <!-- Subtle Background Ambient Accent on Hover -->
              <div class="absolute -right-16 -top-16 w-36 h-36 bg-amber-500/5 rounded-full blur-2xl group-hover:bg-amber-500/10 transition-colors pointer-events-none"></div>

              <div class="flex flex-col sm:flex-row items-start gap-5 sm:gap-6 relative z-10">
                <!-- Left Icon Container & Number Indicator -->
                <div class="flex sm:flex-col items-center sm:items-center gap-3 shrink-0">
                  <div class="w-12 h-12 sm:w-14 sm:h-14 rounded-2xl bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 flex items-center justify-center shadow-xs group-hover:scale-105 transition-transform">
                    <app-icon [name]="item.icon" [size]="24" strokeWidth="1.75"></app-icon>
                  </div>
                  <span class="font-mono text-xs font-semibold text-neutral-400 dark:text-neutral-500 tracking-wider">
                    {{ item.step }}
                  </span>
                </div>

                <!-- Right Feature Content Block -->
                <div class="space-y-3 flex-1 min-w-0">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
                      {{ item.badge }}
                    </span>
                  </div>

                  <h3 class="text-xl sm:text-2xl font-bold text-neutral-900 dark:text-neutral-50 tracking-tight">
                    {{ item.title }}
                  </h3>

                  <p class="text-sm sm:text-base text-neutral-600 dark:text-neutral-300 leading-relaxed font-normal">
                    {{ item.description }}
                  </p>

                  <!-- Highlight Benefit Tag -->
                  <div class="pt-3 border-t border-neutral-100 dark:border-neutral-800 flex items-center gap-2 text-xs text-neutral-500 dark:text-neutral-400">
                    <span class="text-amber-600 dark:text-amber-400 font-semibold">Advantage:</span>
                    <span>{{ item.keyBenefit }}</span>
                  </div>
                </div>
              </div>
            </article>
          }
        </div>

        <!-- Reliability & Fallback Trust Banner -->
        <div class="mt-12 sm:mt-16 p-6 sm:p-7 rounded-2xl border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 shadow-subtle flex flex-col sm:flex-row items-start sm:items-center justify-between gap-5 relative overflow-hidden">
          <div class="flex items-center gap-4">
            <div class="w-12 h-12 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-600 dark:text-emerald-400 flex items-center justify-center shrink-0 shadow-xs">
              <app-icon name="shield-check" [size]="22" strokeWidth="1.75"></app-icon>
            </div>
            <div class="space-y-1">
              <h4 class="font-bold text-sm sm:text-base text-neutral-900 dark:text-neutral-50">
                Dependable & Reliable by Design
              </h4>
              <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 max-w-xl leading-relaxed">
                Every AI feature has a reliable built-in fallback, so your budgeting never breaks even if AI assistance is temporarily unavailable.
              </p>
            </div>
          </div>

          <div class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border border-emerald-500/20 shrink-0">
            <app-icon name="check" [size]="14"></app-icon>
            <span>Guaranteed Continuity</span>
          </div>
        </div>

      </div>
    </section>
  `
})
export class LandingFeaturesComponent {
  readonly aiFeatures: AiFeatureItem[] = [
    {
      id: 'chat-assistant',
      step: '01',
      icon: 'message-square',
      badge: 'Conversational AI Assistant',
      title: 'Talk to Your Money in Plain English',
      description:
        'Meet your campus financial assistant: an interactive companion featuring our squirrel mascot that understands natural questions like "How much did I spend on food this month?" or "Am I getting close to my budget limit?". It remembers context across multiple turns of conversation and operates strictly in read-only mode — meaning it can analyze and report on your finances, but can never create, modify, or delete a transaction on its own, so you can explore freely with complete safety.',
      keyBenefit: 'Read-only safety barrier with multi-turn conversational memory'
    },
    {
      id: 'smart-categorization',
      step: '02',
      icon: 'sparkles',
      badge: 'Smart Category Suggestions',
      title: 'Instant Categorization as You Type',
      description:
        'Logging expenses is friction-free. As you type a transaction description in English or Vietnamese, Campus Coin automatically suggests the right spending category — seamlessly learning from common patterns like "coffee shop" → Coffee & Snacks. Your financial privacy is protected: no amount, date, or sensitive account details are ever shared for categorization, and you always retain total control to accept or change the suggested tag.',
      keyBenefit: 'Zero PII exposure — only the description text is ever evaluated'
    },
    {
      id: 'smart-csv-import',
      step: '03',
      icon: 'upload',
      badge: 'Smart CSV Import',
      title: 'Bulk Bank Statement Import with Auto-Tagging',
      description:
        'Bringing in weeks or months of card records takes seconds instead of hours. When you upload a bank export or spreadsheet file, the importer parses each entry and pre-fills suggested categories in an interactive preview table. You can inspect, adjust, and confirm every single row before anything is committed to your permanent ledger.',
      keyBenefit: 'Automated table pre-fills with full student review before saving'
    },
    {
      id: 'monthly-insights',
      step: '04',
      icon: 'file-text',
      badge: 'Monthly AI Insights',
      title: 'Friendly, Plain-Language Month-End Recaps',
      description:
        'At the end of each month, the assistant writes a friendly, plain-language summary of your spending journey. It highlights what changed, identifies which categories grew the most compared to previous months, and delivers one concrete, actionable tip to help you stay ahead in the upcoming semester.',
      keyBenefit: 'Actionable narrative coaching without tedious spreadsheet math'
    },
    {
      id: 'unusual-activity',
      step: '05',
      icon: 'alert-triangle',
      badge: 'Unusual Activity Detection',
      title: 'Automatic Outlier & Duplicate Charge Detection',
      description:
        'Never let an accidental overcharge or double payment go unnoticed. Campus Coin automatically analyzes your typical spending habits and flags transactions that look out of the ordinary — such as an unexpectedly large expense or an accidental duplicate charge within a short window — so you can verify purchases before they impact your allowance.',
      keyBenefit: 'Proactive detection of abnormal charges and duplicate debits'
    },
    {
      id: 'forecast',
      step: '06',
      icon: 'trending-up',
      badge: 'Spending Forecast',
      title: 'Predict Next Month\'s Expenses Before They Arrive',
      description:
        'Stay one step ahead of your allowance cycle. Based on your recent multi-month spending history and your current pace, the app estimates what next month\'s expenses and cash flow will look like. Each forecast includes an explicit confidence rating, giving you clear visibility to budget for upcoming tuition, textbook, or housing payments.',
      keyBenefit: 'Forward-looking cash flow projections with transparent confidence scores'
    },
    {
      id: 'saving-tips',
      step: '07',
      icon: 'lightbulb',
      badge: 'Personalized Saving Tips',
      title: 'Habit-Driven Tips Tailored to Real Student Life',
      description:
        'Receive practical, real-world financial tips generated directly from your actual habits — such as dining out too frequently, near-limit categories, or an unused subscription. You can easily bookmark your favorite tips into your personal library to build lasting financial wellness throughout your college career.',
      keyBenefit: 'Custom recommendations you can save and bookmark for the long term'
    }
  ];
}
