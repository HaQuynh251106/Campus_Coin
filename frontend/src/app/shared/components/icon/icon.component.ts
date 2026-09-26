import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-icon',
  standalone: true,
  imports: [CommonModule],
  template: `
    <svg
      [attr.width]="size"
      [attr.height]="size"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      [attr.stroke-width]="strokeWidth"
      stroke-linecap="round"
      stroke-linejoin="round"
      [class]="className"
    >
      <!-- Home -->
      @if (name === 'home') {
        <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
      }

      <!-- Quick Add / Plus -->
      @if (name === 'plus' || name === 'plus-circle') {
        <circle cx="12" cy="12" r="10" />
        <path d="M8 12h8" />
        <path d="M12 8v8" />
      }

      @if (name === 'plus-simple') {
        <path d="M5 12h14" />
        <path d="M12 5v14" />
      }

      <!-- Reports / Charts -->
      @if (name === 'pie-chart' || name === 'reports') {
        <path d="M21.21 15.89A10 10 0 1 1 8 2.83" />
        <path d="M22 12A10 10 0 0 0 12 2v10z" />
      }

      @if (name === 'bar-chart' || name === 'bar-chart-2') {
        <line x1="18" y1="20" x2="18" y2="10" />
        <line x1="12" y1="20" x2="12" y2="4" />
        <line x1="6" y1="20" x2="6" y2="14" />
      }

      <!-- Budgets / Wallet -->
      @if (name === 'wallet' || name === 'budgets') {
        <path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1" />
        <path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4" />
      }

      <!-- Insights / Sparkles / AI -->
      @if (name === 'sparkles' || name === 'insights') {
        <path d="m12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3Z" />
      }

      <!-- Categories / Layers / Tag -->
      @if (name === 'tag' || name === 'categories') {
        <path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z" />
        <circle cx="7" cy="7" r=".5" fill="currentColor" />
      }

      <!-- Profile / User -->
      @if (name === 'user' || name === 'profile') {
        <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      }

      @if (name === 'users') {
        <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
      }

      <!-- Categories functional icons -->
      @if (name === 'utensils') {
        <path d="M18 2v6a3 3 0 0 1-3 3 3 3 0 0 1-3-3V2" />
        <path d="M14 2v20" />
        <path d="M5 2v8a3 3 0 0 0 3 3 3 3 0 0 0 3-3V2" />
        <path d="M8 13v9" />
      }

      @if (name === 'coffee') {
        <path d="M17 8h1a4 4 0 1 1 0 8h-1" />
        <path d="M3 8h14v9a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4Z" />
        <line x1="6" y1="2" x2="6" y2="4" />
        <line x1="10" y1="2" x2="10" y2="4" />
        <line x1="14" y1="2" x2="14" y2="4" />
      }

      @if (name === 'book-open') {
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
        <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
      }

      @if (name === 'bus') {
        <path d="M8 6v6" />
        <path d="M16 6v6" />
        <path d="M4 18h16" />
        <rect width="16" height="16" x="4" y="3" rx="2" />
        <path d="M4 11h16" />
        <circle cx="8.5" cy="15.5" r="1.5" />
        <circle cx="15.5" cy="15.5" r="1.5" />
      }

      @if (name === 'home-sub' || name === 'housing') {
        <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
      }

      @if (name === 'gamepad-2') {
        <line x1="6" y1="12" x2="10" y2="12" />
        <line x1="8" y1="10" x2="8" y2="14" />
        <line x1="15" y1="13" x2="15.01" y2="13" />
        <line x1="18" y1="11" x2="18.01" y2="11" />
        <rect width="20" height="12" x="2" y="6" rx="6" />
      }

      @if (name === 'laptop') {
        <path d="M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16" />
      }

      @if (name === 'heart-pulse') {
        <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
        <path d="M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27" />
      }

      @if (name === 'briefcase') {
        <rect width="20" height="14" x="2" y="7" rx="2" />
        <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
      }

      @if (name === 'award') {
        <circle cx="12" cy="8" r="6" />
        <path d="M15.477 12.89 17 22l-5-3-5 3 1.523-9.11" />
      }

      @if (name === 'gift') {
        <rect x="3" y="8" width="18" height="4" rx="1" />
        <path d="M12 8v13" />
        <path d="M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7" />
        <path d="M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5" />
      }

      @if (name === 'code') {
        <polyline points="16 18 22 12 16 6" />
        <polyline points="8 6 2 12 8 18" />
      }

      @if (name === 'plane') {
        <path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z" />
      }

      <!-- UI Utility Icons -->
      @if (name === 'sun') {
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2" />
        <path d="M12 20v2" />
        <path d="m4.93 4.93 1.41 1.41" />
        <path d="m17.66 17.66 1.41 1.41" />
        <path d="M2 12h2" />
        <path d="M20 12h2" />
        <path d="m6.34 17.66-1.41 1.41" />
        <path d="m19.07 4.93-1.41 1.41" />
      }

      @if (name === 'moon') {
        <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
      }

      @if (name === 'arrow-up-right') {
        <path d="M7 7h10v10" />
        <path d="M7 17 17 7" />
      }

      @if (name === 'arrow-down-left') {
        <path d="M17 17H7V7" />
        <path d="m17 7-10 10" />
      }

      @if (name === 'trash-2') {
        <path d="M3 6h18" />
        <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6" />
        <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2" />
        <line x1="10" y1="11" x2="10" y2="17" />
        <line x1="14" y1="11" x2="14" y2="17" />
      }

      @if (name === 'edit-2') {
        <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
      }

      @if (name === 'search') {
        <circle cx="11" cy="11" r="8" />
        <path d="m21 21-4.3-4.3" />
      }

      @if (name === 'filter') {
        <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
      }

      @if (name === 'download') {
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" y1="15" x2="12" y2="3" />
      }

      @if (name === 'upload') {
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="17 8 12 3 7 8" />
        <line x1="12" y1="3" x2="12" y2="15" />
      }

      @if (name === 'check') {
        <polyline points="20 6 9 17 4 12" />
      }

      @if (name === 'x') {
        <path d="M18 6 6 18" />
        <path d="m6 6 12 12" />
      }

      @if (name === 'chevron-right') {
        <path d="m9 18 6-6-6-6" />
      }

      @if (name === 'bookmark') {
        <path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z" />
      }

      @if (name === 'alert-triangle') {
        <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      }

      @if (name === 'log-out') {
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
        <polyline points="16 17 21 12 16 7" />
        <line x1="21" y1="12" x2="9" y2="12" />
      }

      @if (name === 'map') {
        <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" />
        <line x1="9" y1="3" x2="9" y2="18" />
        <line x1="15" y1="6" x2="15" y2="21" />
      }

      @if (name === 'file-text') {
        <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
        <path d="M14 2v4a2 2 0 0 0 2 2h4" />
        <path d="M10 9H8" />
        <path d="M16 13H8" />
        <path d="M16 17H8" />
      }

      <!-- Landing & Marketing Icons -->
      @if (name === 'menu') {
        <line x1="4" x2="20" y1="12" y2="12" />
        <line x1="4" x2="20" y1="6" y2="6" />
        <line x1="4" x2="20" y1="18" y2="18" />
      }

      @if (name === 'arrow-right') {
        <path d="M5 12h14" />
        <path d="m12 5 7 7-7 7" />
      }

      @if (name === 'arrow-up') {
        <path d="m18 15-6-6-6 6" />
      }

      @if (name === 'check-circle') {
        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
        <polyline points="22 4 12 14.01 9 11.01" />
      }

      @if (name === 'shield-check') {
        <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
        <path d="m9 12 2 2 4-4" />
      }

      @if (name === 'zap') {
        <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
      }

      @if (name === 'trending-up') {
        <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
        <polyline points="16 7 22 7 22 13" />
      }

      @if (name === 'bell') {
        <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
        <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
      }

      @if (name === 'message-square') {
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      }

      <!-- Squirrel Brand Identity Icons -->
      @if (name === 'squirrel-logo' || name === 'squirrel-coin') {
        <!-- Bushy curved tail -->
        <path d="M15 19c3 0 5-2 5-5.5 0-3-2-5.5-4.5-5.5-1.5 0-2.5.8-3 2" />
        <!-- Squirrel head and ears -->
        <path d="M7 6.5 6 3l3 2" />
        <path d="M12 6.5l1-3.5-3 2" />
        <path d="M5.5 11.5c-1 0-1.5.8-1.5 1.8 0 1.5 1.5 2.7 3.5 2.7" />
        <circle cx="9" cy="11.5" r="4.5" />
        <circle cx="8" cy="11" r=".75" fill="currentColor" />
        <!-- Paws holding gold coin -->
        <circle cx="13" cy="16.5" r="3.5" />
        <path d="M13 15v3" />
        <path d="M11.5 16.5h3" />
        <!-- Front paws gripping coin -->
        <path d="M9.5 16c.8.2 1.5-.2 2-.5" />
      }

      <!-- Education / Graduation Cap (Scholarship / Academics) -->
      @if (name === 'graduation-cap' || name === 'education' || name === 'scholarship') {
        <path d="M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z" />
        <path d="M22 10v6" />
        <path d="M6 12.5V16a6 3 0 0 0 12 0v-3.5" />
      }

      <!-- Repeat / Subscriptions -->
      @if (name === 'repeat' || name === 'subscriptions') {
        <path d="m17 2 4 4-4 4" />
        <path d="M3 11v-1a4 4 0 0 1 4-4h14" />
        <path d="m7 22-4-4 4-4" />
        <path d="M21 13v1a4 4 0 0 1-4 4H3" />
      }

      <!-- Film / Entertainment -->
      @if (name === 'film' || name === 'entertainment') {
        <rect width="20" height="20" x="2" y="2" rx="2.18" ry="2.18" />
        <line x1="7" x2="7" y1="2" y2="22" />
        <line x1="17" x2="17" y1="2" y2="22" />
        <line x1="2" x2="22" y1="12" y2="12" />
        <line x1="2" x2="7" y1="7" y2="7" />
        <line x1="2" x2="7" y1="17" y2="17" />
        <line x1="17" x2="22" y1="17" y2="17" />
        <line x1="17" x2="22" y1="7" y2="7" />
      }

      <!-- More Horizontal / Miscellaneous -->
      @if (name === 'more-horizontal' || name === 'more' || name === 'miscellaneous') {
        <circle cx="12" cy="12" r="1.5" fill="currentColor" />
        <circle cx="19" cy="12" r="1.5" fill="currentColor" />
        <circle cx="5" cy="12" r="1.5" fill="currentColor" />
      }

      <!-- Shopping Bag -->
      @if (name === 'shopping-bag' || name === 'shopping') {
        <path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z" />
        <path d="M3 6h18" />
        <path d="M16 10a4 4 0 0 1-8 0" />
      }

      <!-- Music -->
      @if (name === 'music') {
        <path d="M9 18V5l12-2v13" />
        <circle cx="6" cy="18" r="3" />
        <circle cx="18" cy="16" r="3" />
      }

      <!-- Dumbbell / Fitness -->
      @if (name === 'dumbbell' || name === 'fitness') {
        <path d="m6.5 6.5 11 11" />
        <path d="m21 21-1-1" />
        <path d="m3 3 1 1" />
        <path d="m18 22 4-4" />
        <path d="m2 6 4-4" />
        <path d="m3 10 7-7" />
        <path d="m14 21 7-7" />
      }

      <!-- Shirt / Clothing -->
      @if (name === 'shirt') {
        <path d="M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z" />
      }

      <!-- Fallback Default Icon when name is unknown -->
      @if (isFallback) {
        <path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z" />
        <circle cx="7" cy="7" r=".5" fill="currentColor" />
      }
    </svg>
  `
})
export class IconComponent {
  @Input() name: string = 'tag';
  @Input() size: number | string = 20;
  @Input() strokeWidth: number | string = 1.5;
  @Input() className: string = '';

  private readonly KNOWN_ICONS = new Set([
    'home', 'plus', 'plus-circle', 'plus-simple', 'pie-chart', 'reports', 'bar-chart', 'bar-chart-2',
    'wallet', 'budgets', 'sparkles', 'insights', 'tag', 'categories', 'user', 'profile', 'users',
    'utensils', 'coffee', 'book-open', 'bus', 'home-sub', 'housing', 'gamepad-2', 'laptop',
    'heart-pulse', 'briefcase', 'award', 'gift', 'code', 'plane', 'sun', 'moon', 'arrow-up-right',
    'arrow-down-left', 'trash-2', 'edit-2', 'search', 'filter', 'download', 'upload', 'check',
    'x', 'chevron-right', 'bookmark', 'alert-triangle', 'log-out', 'map', 'file-text', 'menu',
    'arrow-right', 'arrow-up', 'check-circle', 'shield-check', 'zap', 'trending-up', 'bell', 'message-square',
    'squirrel-logo', 'squirrel-coin', 'graduation-cap', 'education', 'scholarship', 'repeat',
    'subscriptions', 'film', 'entertainment', 'more-horizontal', 'more', 'miscellaneous',
    'shopping-bag', 'shopping', 'music', 'dumbbell', 'fitness', 'shirt'
  ]);

  get isFallback(): boolean {
    return !this.name || !this.KNOWN_ICONS.has(this.name);
  }
}
