import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { InsightService } from '../../core/services/insight.service';
import { MonthlyInsight } from '../../core/models/insight.model';
import { BreadcrumbsComponent } from '../../shared/components/breadcrumbs/breadcrumbs.component';
import { CardComponent } from '../../shared/components/card/card.component';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { IconComponent } from '../../shared/components/icon/icon.component';

@Component({
  selector: 'app-insights',
  standalone: true,
  imports: [
    CommonModule,
    BreadcrumbsComponent,
    IconComponent
  ],
  template: `
    <div class="space-y-6">
      <!-- Breadcrumbs -->
      <app-breadcrumbs
        [items]="[{ label: 'AI Monthly Insights' }]"
      ></app-breadcrumbs>

      <!-- Page Header -->
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            AI Financial Insights & Stories
          </h2>
          <p class="text-xs sm:text-sm font-medium text-neutral-500 dark:text-neutral-400">
            Personalized narrative spending reviews and student savings hacks.
          </p>
        </div>

        <!-- Filter Toggle (All vs Bookmarked) -->
        <div class="flex items-center gap-1 bg-neutral-100 dark:bg-neutral-800 p-1 rounded-lg border border-neutral-200 dark:border-neutral-700">
          <button
            type="button"
            (click)="showOnlyBookmarked = false"
            class="px-3 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-colors"
            [class.bg-white]="!showOnlyBookmarked"
            [class.dark:bg-neutral-700]="!showOnlyBookmarked"
            [class.text-neutral-950]="!showOnlyBookmarked"
            [class.dark:text-white]="!showOnlyBookmarked"
            [class.shadow-xs]="!showOnlyBookmarked"
            [class.text-neutral-500]="showOnlyBookmarked"
          >
            All Monthly Insights
          </button>
          <button
            type="button"
            (click)="showOnlyBookmarked = true"
            class="px-3 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-colors flex items-center gap-1.5"
            [class.bg-white]="showOnlyBookmarked"
            [class.dark:bg-neutral-700]="showOnlyBookmarked"
            [class.text-amber-600]="showOnlyBookmarked"
            [class.dark:text-amber-400]="showOnlyBookmarked"
            [class.shadow-xs]="showOnlyBookmarked"
            [class.text-neutral-500]="!showOnlyBookmarked"
          >
            <app-icon name="bookmark" [size]="12" strokeWidth="1.5"></app-icon>
            <span>Pinned Tips ({{ bookmarkedCount }})</span>
          </button>
        </div>
      </div>

      <!-- Insights List -->
      <div class="space-y-4">
        @for (insight of filteredInsights; track insight.id) {
          <div class="card-brutal p-6 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs transition-colors hover:border-neutral-300 dark:hover:border-neutral-700">

            <!-- Top Card Header -->
            <div class="flex items-start justify-between gap-4 mb-3">
              <div class="flex items-center gap-3">
                <!-- Sourced Real Photo for AI Advisor -->
                <div class="w-11 h-11 rounded-lg border border-neutral-200 dark:border-neutral-700 overflow-hidden bg-neutral-100 dark:bg-neutral-800 shrink-0">
                  <img
                    [src]="insight.avatarUrl"
                    alt="AI Financial Advisor Avatar"
                    class="w-full h-full object-cover"
                  />
                </div>
                <div>
                  <div class="flex items-center gap-2">
                    <span class="inline-flex items-center text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20">
                      {{ insight.month }}
                    </span>
                    @if (insight.categoryFlag) {
                      <span class="text-[10px] font-medium px-2 py-0.5 rounded-full" [class.bg-rose-50]="insight.categoryFlag.direction === 'UP'" [class.text-rose-700]="insight.categoryFlag.direction === 'UP'" [class.border]="true" [class.border-rose-200]="insight.categoryFlag.direction === 'UP'" [class.bg-emerald-50]="insight.categoryFlag.direction === 'DOWN'" [class.text-emerald-700]="insight.categoryFlag.direction === 'DOWN'" [class.border-emerald-200]="insight.categoryFlag.direction === 'DOWN'">
                        {{ insight.categoryFlag.categoryName }} ({{ insight.categoryFlag.direction === 'UP' ? '+' : '-' }}{{ insight.categoryFlag.percentChange }}%)
                      </span>
                    }
                  </div>
                  <h3 class="font-semibold text-base text-neutral-900 dark:text-neutral-50 mt-1">
                    {{ insight.title }}
                  </h3>
                </div>
              </div>

              <!-- Bookmark / Pin Toggle -->
              <button
                type="button"
                (click)="toggleBookmark(insight)"
                [attr.aria-label]="insight.isBookmarked ? 'Remove bookmark' : 'Bookmark this tip'"
                class="p-2 rounded-lg border transition-colors cursor-pointer"
                [class.border-amber-500]="insight.isBookmarked"
                [class.bg-amber-500/10]="insight.isBookmarked"
                [class.text-amber-600]="insight.isBookmarked"
                [class.dark:text-amber-400]="insight.isBookmarked"
                [class.border-neutral-200]="!insight.isBookmarked"
                [class.dark:border-neutral-700]="!insight.isBookmarked"
                [class.bg-white]="!insight.isBookmarked"
                [class.dark:bg-neutral-800]="!insight.isBookmarked"
                [class.text-neutral-400]="!insight.isBookmarked"
                [class.hover:text-neutral-700]="!insight.isBookmarked"
                [class.dark:hover:text-neutral-200]="!insight.isBookmarked"
              >
                <app-icon name="bookmark" [size]="15" strokeWidth="1.5"></app-icon>
              </button>
            </div>

            <!-- Narrative Story Text -->
            <p class="text-xs sm:text-sm text-neutral-600 dark:text-neutral-300 leading-relaxed mb-4">
              {{ insight.narrativeSummary }}
            </p>

            <!-- Actionable Student Saving Tip -->
            <div class="p-3.5 bg-amber-500/5 dark:bg-amber-500/10 border border-amber-500/20 rounded-lg flex items-start gap-3">
              <span class="text-base">💡</span>
              <div>
                <span class="text-[10px] font-semibold uppercase text-amber-800 dark:text-amber-400 tracking-wider block">
                  Actionable Student Tip:
                </span>
                <p class="text-xs font-medium text-neutral-900 dark:text-neutral-100 mt-0.5 leading-snug">
                  {{ insight.savingTip }}
                </p>
              </div>
            </div>

          </div>
        }

        @if (filteredInsights.length === 0) {
          <div class="card-brutal p-8 text-center bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl text-neutral-500">
            <span class="text-2xl block mb-2">🔖</span>
            <h4 class="font-medium text-base text-neutral-800 dark:text-neutral-200">
              No Bookmarked Insights Yet
            </h4>
            <p class="text-xs mt-1 text-neutral-400">
              Click the bookmark icon on any monthly card above to save high-value student saving tips here.
            </p>
          </div>
        }
      </div>

    </div>
  `
})
export class InsightsComponent implements OnInit {
  private insightService = inject(InsightService);

  allInsights: MonthlyInsight[] = [];
  showOnlyBookmarked = false;

  get bookmarkedCount(): number {
    return this.allInsights.filter(i => i.isBookmarked).length;
  }

  get filteredInsights(): MonthlyInsight[] {
    if (this.showOnlyBookmarked) {
      return this.allInsights.filter(i => i.isBookmarked);
    }
    return this.allInsights;
  }

  ngOnInit(): void {
    this.insightService.getInsights().subscribe(list => {
      this.allInsights = list;
    });
  }

  toggleBookmark(insight: MonthlyInsight): void {
    this.insightService.toggleBookmark(insight.id).subscribe(nextState => {
      insight.isBookmarked = nextState;
    });
  }
}
