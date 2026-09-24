import { Injectable, signal, effect, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { FontSizePreference } from '../models/user.model';

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  // Reactive State Signals
  readonly isDarkMode = signal<boolean>(false);
  readonly fontSize = signal<FontSizePreference>('medium');

  constructor() {
    if (this.isBrowser) {
      // 1. Initialize Dark Mode from localStorage or system preference
      const savedTheme = localStorage.getItem('campus_coin_dark_mode');
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      const initialDark = savedTheme !== null ? savedTheme === 'true' : prefersDark;
      this.isDarkMode.set(initialDark);

      // 2. Initialize Font Size from localStorage
      const savedSize = localStorage.getItem('campus_coin_font_size') as FontSizePreference;
      if (savedSize && ['small', 'medium', 'large'].includes(savedSize)) {
        this.fontSize.set(savedSize);
      }

      // 3. Effects to synchronize with DOM
      this.applyTheme(initialDark);
      this.applyFontSize(this.fontSize());
    }
  }

  toggleDarkMode(): void {
    const next = !this.isDarkMode();
    this.isDarkMode.set(next);
    if (this.isBrowser) {
      localStorage.setItem('campus_coin_dark_mode', String(next));
      this.applyTheme(next);
    }
  }

  setDarkMode(dark: boolean): void {
    this.isDarkMode.set(dark);
    if (this.isBrowser) {
      localStorage.setItem('campus_coin_dark_mode', String(dark));
      this.applyTheme(dark);
    }
  }

  setFontSize(size: FontSizePreference): void {
    this.fontSize.set(size);
    if (this.isBrowser) {
      localStorage.setItem('campus_coin_font_size', size);
      this.applyFontSize(size);
    }
  }

  private applyTheme(dark: boolean): void {
    if (!this.isBrowser) return;
    const root = document.documentElement;
    if (dark) {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
  }

  private applyFontSize(size: FontSizePreference): void {
    if (!this.isBrowser) return;
    const root = document.documentElement;
    root.classList.remove('text-size-sm', 'text-size-md', 'text-size-lg');
    root.classList.add(`text-size-${size}`);
  }
}
