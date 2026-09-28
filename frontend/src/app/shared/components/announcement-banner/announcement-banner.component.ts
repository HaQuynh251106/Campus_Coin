import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DashboardAnnouncement } from '../../../core/models/dashboard.model';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-announcement-banner',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <div
      class="rounded-xl p-4 flex items-start gap-3.5 transition-colors shadow-xs"
      [ngClass]="cardClasses()"
    >
      <div
        class="w-8 h-8 rounded-lg flex items-center justify-center shrink-0 mt-0.5"
        [ngClass]="iconContainerClasses()"
      >
        <app-icon [name]="icon()" [size]="18" strokeWidth="1.75"></app-icon>
      </div>

      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h4 [ngClass]="titleClasses()">{{ announcement.title }}</h4>
          <span [ngClass]="badgeClasses()">
            @if (isWarning()) {
              <app-icon name="alert-triangle" [size]="11" strokeWidth="2" className="inline-block mr-0.5"></app-icon>
            }
            {{ announcement.severity }}
          </span>
        </div>

        <p [ngClass]="bodyClasses()">{{ announcement.body }}</p>

        <p [ngClass]="dateClasses()">
          {{ announcement.endsAt ? 'From ' + format(announcement.startsAt) + ' until ' + format(announcement.endsAt) : 'From ' + format(announcement.startsAt) }}
        </p>
      </div>
    </div>
  `
})
export class AnnouncementBannerComponent {
  @Input({ required: true }) announcement!: DashboardAnnouncement;

  isWarning(): boolean {
    return this.announcement.severity === 'WARNING';
  }

  cardClasses(): string {
    if (this.isWarning()) {

      return 'bg-amber-100/90 dark:bg-amber-950/50 border-2 border-amber-500 dark:border-amber-400 border-l-4 border-l-amber-600 dark:border-l-amber-400 shadow-sm';
    }

    return 'bg-amber-50/70 dark:bg-amber-950/20 border border-amber-200/90 dark:border-amber-800/40 border-l-4 border-l-amber-500';
  }

  iconContainerClasses(): string {
    if (this.isWarning()) {
      return 'bg-amber-500 text-neutral-950 dark:bg-amber-400 dark:text-neutral-950 shadow-xs ring-2 ring-amber-500/30';
    }
    return 'bg-amber-500/15 dark:bg-amber-500/20 border border-amber-500/30 text-amber-700 dark:text-amber-400';
  }

  badgeClasses(): string {
    if (this.isWarning()) {
      return 'bg-amber-500 text-neutral-950 dark:bg-amber-400 dark:text-neutral-950 font-bold px-2 py-0.5 rounded text-[10px] tracking-wider uppercase shadow-xs flex items-center gap-1';
    }
    return 'bg-amber-200/60 dark:bg-amber-900/40 text-amber-900 dark:text-amber-300 border border-amber-300/50 dark:border-amber-700/50 font-semibold px-2 py-0.5 rounded text-[10px] tracking-wider uppercase';
  }

  titleClasses(): string {
    if (this.isWarning()) {
      return 'font-bold text-sm text-amber-950 dark:text-amber-100';
    }
    return 'font-semibold text-sm text-neutral-900 dark:text-neutral-100';
  }

  bodyClasses(): string {
    if (this.isWarning()) {
      return 'text-xs mt-1 whitespace-pre-line text-amber-900 dark:text-amber-200 font-medium leading-relaxed';
    }
    return 'text-xs mt-1 whitespace-pre-line text-neutral-600 dark:text-neutral-300 leading-relaxed';
  }

  dateClasses(): string {
    if (this.isWarning()) {
      return 'text-[11px] mt-1.5 text-amber-800/80 dark:text-amber-300/80 font-medium';
    }
    return 'text-[11px] mt-1.5 text-neutral-500 dark:text-neutral-400';
  }

  icon(): string {
    switch (this.announcement.severity) {
      case 'WARNING':
        return 'alert-triangle';
      case 'SUCCESS':
        return 'check-circle';
      case 'INFO':
      default:
        return 'bell';
    }
  }

  format(value: string): string {
    const d = new Date(value);
    if (isNaN(d.getTime())) return value;
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }
}
