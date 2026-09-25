import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../icon/icon.component';

export interface CategoryHue {
  text: string;
  bgDark: string;
  borderDark: string;
  bgLight: string;
  borderLight: string;
}

// 8 curated non-gold category hues
const CURATED_CATEGORY_HUES: Record<string, CategoryHue> = {
  orange: {
    text: '#EA580C',
    bgDark: 'rgba(234, 88, 12, 0.15)',
    borderDark: 'rgba(234, 88, 12, 0.30)',
    bgLight: 'rgba(234, 88, 12, 0.10)',
    borderLight: 'rgba(234, 88, 12, 0.22)'
  },
  teal: {
    text: '#0D9488',
    bgDark: 'rgba(13, 148, 136, 0.15)',
    borderDark: 'rgba(13, 148, 136, 0.30)',
    bgLight: 'rgba(13, 148, 136, 0.10)',
    borderLight: 'rgba(13, 148, 136, 0.22)'
  },
  sky: {
    text: '#0EA5E9',
    bgDark: 'rgba(14, 165, 233, 0.15)',
    borderDark: 'rgba(14, 165, 233, 0.30)',
    bgLight: 'rgba(14, 165, 233, 0.10)',
    borderLight: 'rgba(14, 165, 233, 0.22)'
  },
  purple: {
    text: '#8B5CF6',
    bgDark: 'rgba(139, 92, 246, 0.15)',
    borderDark: 'rgba(139, 92, 246, 0.30)',
    bgLight: 'rgba(139, 92, 246, 0.10)',
    borderLight: 'rgba(139, 92, 246, 0.22)'
  },
  cyan: {
    text: '#06B6D4',
    bgDark: 'rgba(6, 182, 212, 0.15)',
    borderDark: 'rgba(6, 182, 212, 0.30)',
    bgLight: 'rgba(6, 182, 212, 0.10)',
    borderLight: 'rgba(6, 182, 212, 0.22)'
  },
  rose: {
    text: '#F43F5E',
    bgDark: 'rgba(244, 63, 94, 0.15)',
    borderDark: 'rgba(244, 63, 94, 0.30)',
    bgLight: 'rgba(244, 63, 94, 0.10)',
    borderLight: 'rgba(244, 63, 94, 0.22)'
  },
  indigo: {
    text: '#6366F1',
    bgDark: 'rgba(99, 102, 241, 0.15)',
    borderDark: 'rgba(99, 102, 241, 0.30)',
    bgLight: 'rgba(99, 102, 241, 0.10)',
    borderLight: 'rgba(99, 102, 241, 0.22)'
  },
  emerald: {
    text: '#10B981',
    bgDark: 'rgba(16, 185, 129, 0.15)',
    borderDark: 'rgba(16, 185, 129, 0.30)',
    bgLight: 'rgba(16, 185, 129, 0.10)',
    borderLight: 'rgba(16, 185, 129, 0.22)'
  }
};

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
  'transport': 'cyan',
  'transit': 'cyan',
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
  '#6366f1': 'indigo',
  '#818cf8': 'indigo',
  '#fb7185': 'indigo',
  '#10b981': 'emerald',
  '#34d399': 'emerald',
  '#22c55e': 'emerald',
  // Redirect accidental gold/yellow away from brand accent
  '#ffe600': 'orange',
  '#eab308': 'orange',
  '#f59e0b': 'orange'
};

@Component({
  selector: 'app-category-icon',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <div
      class="category-icon-avatar shrink-0 rounded-lg flex items-center justify-center transition-colors select-none"
      [class.w-7]="size === 'sm'"
      [class.h-7]="size === 'sm'"
      [class.w-9]="size === 'md'"
      [class.h-9]="size === 'md'"
      [class.w-10]="size === 'lg'"
      [class.h-10]="size === 'lg'"
      [style.--avatar-text]="hue.text"
      [style.--avatar-bg-dark]="hue.bgDark"
      [style.--avatar-border-dark]="hue.borderDark"
      [style.--avatar-bg-light]="hue.bgLight"
      [style.--avatar-border-light]="hue.borderLight"
    >
      <app-icon
        [name]="icon || 'tag'"
        [size]="iconPixelSize"
        strokeWidth="1.5"
        [style.color]="hue.text"
      ></app-icon>
    </div>
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
export class CategoryIconComponent {
  @Input() name: string = '';
  @Input() icon: string = 'tag';
  @Input() color?: string;
  @Input() size: 'sm' | 'md' | 'lg' = 'md';

  get iconPixelSize(): number {
    return this.size === 'sm' ? 14 : this.size === 'md' ? 16 : 18;
  }

  get hue(): CategoryHue {
    if (this.color) {
      const lower = this.color.toLowerCase();
      const match = HEX_TO_HUE[lower];
      if (match && CURATED_CATEGORY_HUES[match]) {
        return CURATED_CATEGORY_HUES[match];
      }
    }

    if (this.name) {
      const lowerName = this.name.toLowerCase().trim();
      const match = NAME_TO_HUE[lowerName];
      if (match && CURATED_CATEGORY_HUES[match]) {
        return CURATED_CATEGORY_HUES[match];
      }
    }

    // Dynamic fallback if hex provided
    if (this.color && /^#[0-9A-Fa-f]{6}$/.test(this.color)) {
      const r = parseInt(this.color.slice(1, 3), 16);
      const g = parseInt(this.color.slice(3, 5), 16);
      const b = parseInt(this.color.slice(5, 7), 16);
      return {
        text: this.color,
        bgDark: `rgba(${r}, ${g}, ${b}, 0.15)`,
        borderDark: `rgba(${r}, ${g}, ${b}, 0.30)`,
        bgLight: `rgba(${r}, ${g}, ${b}, 0.10)`,
        borderLight: `rgba(${r}, ${g}, ${b}, 0.22)`
      };
    }

    return CURATED_CATEGORY_HUES['sky'];
  }
}
