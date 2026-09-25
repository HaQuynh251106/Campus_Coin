import { Component, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../icon/icon.component';

export interface CategoryColorDef {
  dark: { text: string; bg: string; border: string };
  light: { text: string; bg: string; border: string };
}

// Curated 8-hue categorical palette strictly excluding gold/amber
const CURATED_HUES: Record<string, CategoryColorDef> = {
  emerald: {
    dark: { text: '#34D399', bg: 'rgba(16, 185, 129, 0.15)', border: 'rgba(16, 185, 129, 0.35)' },
    light: { text: '#047857', bg: 'rgba(16, 185, 129, 0.10)', border: 'rgba(16, 185, 129, 0.25)' }
  },
  orange: {
    dark: { text: '#FB923C', bg: 'rgba(234, 88, 12, 0.15)', border: 'rgba(234, 88, 12, 0.35)' },
    light: { text: '#C2410C', bg: 'rgba(234, 88, 12, 0.10)', border: 'rgba(234, 88, 12, 0.25)' }
  },
  sky: {
    dark: { text: '#38BDF8', bg: 'rgba(14, 165, 233, 0.15)', border: 'rgba(14, 165, 233, 0.35)' },
    light: { text: '#0369A1', bg: 'rgba(14, 165, 233, 0.10)', border: 'rgba(14, 165, 233, 0.25)' }
  },
  purple: {
    dark: { text: '#A78BFA', bg: 'rgba(139, 92, 246, 0.15)', border: 'rgba(139, 92, 246, 0.35)' },
    light: { text: '#6D28D9', bg: 'rgba(139, 92, 246, 0.10)', border: 'rgba(139, 92, 246, 0.25)' }
  },
  teal: {
    dark: { text: '#2DD4BF', bg: 'rgba(13, 148, 136, 0.15)', border: 'rgba(13, 148, 136, 0.35)' },
    light: { text: '#0F766E', bg: 'rgba(13, 148, 136, 0.10)', border: 'rgba(13, 148, 136, 0.25)' }
  },
  cyan: {
    dark: { text: '#22D3EE', bg: 'rgba(6, 182, 212, 0.15)', border: 'rgba(6, 182, 212, 0.35)' },
    light: { text: '#0E7490', bg: 'rgba(6, 182, 212, 0.10)', border: 'rgba(6, 182, 212, 0.25)' }
  },
  rose: {
    dark: { text: '#FB7185', bg: 'rgba(244, 63, 94, 0.15)', border: 'rgba(244, 63, 94, 0.35)' },
    light: { text: '#BE123C', bg: 'rgba(244, 63, 94, 0.10)', border: 'rgba(244, 63, 94, 0.25)' }
  },
  indigo: {
    dark: { text: '#818CF8', bg: 'rgba(99, 102, 241, 0.15)', border: 'rgba(99, 102, 241, 0.35)' },
    light: { text: '#4338CA', bg: 'rgba(99, 102, 241, 0.10)', border: 'rgba(99, 102, 241, 0.25)' }
  }
};

// Map category names or hex to one of the 8 curated hues
const NAME_TO_HUE: Record<string, string> = {
  'food & dining': 'orange',
  'dining': 'orange',
  'food': 'orange',
  'coffee & snacks': 'teal',
  'coffee': 'teal',
  'snacks': 'teal',
  'books & supplies': 'sky',
  'books': 'sky',
  'supplies': 'sky',
  'housing & utilities': 'purple',
  'housing': 'purple',
  'transport & commute': 'cyan',
  'transit': 'cyan',
  'transport': 'cyan',
  'entertainment & social': 'rose',
  'entertainment': 'rose',
  'social': 'rose',
  'tech & subscriptions': 'indigo',
  'tech': 'indigo',
  'subscriptions': 'indigo',
  'health & fitness': 'emerald',
  'health': 'emerald',
  'fitness': 'emerald',
  'part-time job': 'emerald',
  'job': 'emerald',
  'scholarship & grants': 'indigo',
  'scholarship': 'indigo',
  'family allowance': 'sky',
  'allowance': 'sky',
  'freelance & tutoring': 'purple',
  'freelance': 'purple',
  'tutoring': 'purple',
  'hackathon travel': 'rose'
};

const HEX_TO_HUE: Record<string, string> = {
  '#ea580c': 'orange',
  '#f97316': 'orange',
  '#0d9488': 'teal',
  '#14b8a6': 'teal',
  '#0ea5e9': 'sky',
  '#38bdf8': 'sky',
  '#8b5cf6': 'purple',
  '#a78bfa': 'purple',
  '#06b6d4': 'cyan',
  '#00f5a0': 'cyan',
  '#f43f5e': 'rose',
  '#ff6b6b': 'rose',
  '#ec4899': 'rose',
  '#fb7185': 'indigo',
  '#6366f1': 'indigo',
  '#818cf8': 'indigo',
  '#10b981': 'emerald',
  '#34d399': 'emerald',
  '#22c55e': 'emerald',
  // Guard against any accidental gold/yellow mapped to category: redirect to orange/teal
  '#ffe600': 'orange',
  '#eab308': 'orange',
  '#f59e0b': 'orange'
};

@Component({
  selector: 'app-category-tag',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <span class="inline-flex items-center gap-2 select-none">
      <span
        class="category-icon-avatar shrink-0 rounded-lg flex items-center justify-center transition-colors"
        [class.w-6]="size === 'xs'"
        [class.h-6]="size === 'xs'"
        [class.w-7]="size === 'sm'"
        [class.h-7]="size === 'sm'"
        [class.w-8]="size === 'md'"
        [class.h-8]="size === 'md'"
        [style.--avatar-text]="styles.dark.text"
        [style.--avatar-bg-dark]="styles.dark.bg"
        [style.--avatar-border-dark]="styles.dark.border"
        [style.--avatar-bg-light]="styles.light.bg"
        [style.--avatar-border-light]="styles.light.border"
      >
        @if (icon) {
          <app-icon
            [name]="icon"
            [size]="iconSize"
            strokeWidth="1.5"
            [style.color]="styles.dark.text"
          ></app-icon>
        }
      </span>
      <span class="text-xs text-[var(--color-text-muted)] font-medium truncate max-w-[150px]">{{ name }}</span>
    </span>
  `,
  styles: [`
    .category-icon-avatar {
      background-color: var(--avatar-bg-light);
      border: 1px solid var(--avatar-border-light);
      color: var(--avatar-text);
    }
    :host-context(html.dark) .category-icon-avatar,
    :host-context(.dark) .category-icon-avatar {
      background-color: var(--avatar-bg-dark);
      border-color: var(--avatar-border-dark);
      color: var(--avatar-text);
    }
  `]
})
export class CategoryTagComponent {
  @Input() name: string = '';
  @Input() icon?: string;
  @Input() color?: string;
  @Input() size: 'xs' | 'sm' | 'md' = 'sm';

  get iconSize(): number {
    return this.size === 'xs' ? 11 : this.size === 'sm' ? 12 : 14;
  }

  get styles(): CategoryColorDef {
    // 1. Try color hex lookup
    if (this.color) {
      const lower = this.color.toLowerCase();
      const hueKey = HEX_TO_HUE[lower];
      if (hueKey && CURATED_HUES[hueKey]) {
        return CURATED_HUES[hueKey];
      }
    }

    // 2. Try name lookup
    if (this.name) {
      const lowerName = this.name.toLowerCase().trim();
      const hueKey = NAME_TO_HUE[lowerName];
      if (hueKey && CURATED_HUES[hueKey]) {
        return CURATED_HUES[hueKey];
      }
    }

    // 3. Fallback to custom color calculation if valid hex provided
    if (this.color && /^#[0-9A-Fa-f]{6}$/.test(this.color)) {
      return this.computeDynamicStyle(this.color);
    }

    // Default to Sky hue
    return CURATED_HUES['sky'];
  }

  private computeDynamicStyle(hex: string): CategoryColorDef {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);

    return {
      dark: {
        text: hex,
        bg: `rgba(${r}, ${g}, ${b}, 0.15)`,
        border: `rgba(${r}, ${g}, ${b}, 0.35)`
      },
      light: {
        text: `rgb(${Math.max(0, Math.floor(r * 0.7))}, ${Math.max(0, Math.floor(g * 0.7))}, ${Math.max(0, Math.floor(b * 0.7))})`,
        bg: `rgba(${r}, ${g}, ${b}, 0.10)`,
        border: `rgba(${r}, ${g}, ${b}, 0.25)`
      }
    };
  }
}
