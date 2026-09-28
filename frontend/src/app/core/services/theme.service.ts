import { Injectable, signal, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { FontSizePreference, ServerFontScale } from '../models/user.model';

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  readonly isDarkMode = signal<boolean>(false);
  readonly fontSize = signal<FontSizePreference>('medium');

  constructor() {
    if (this.isBrowser) {

      const savedTheme = localStorage.getItem('campus_coin_dark_mode');
      const prefersDark =
        typeof window !== 'undefined' && typeof window.matchMedia === 'function'
          ? window.matchMedia('(prefers-color-scheme: dark)').matches
          : false;
      const initialDark = savedTheme !== null ? savedTheme === 'true' : prefersDark;
      this.isDarkMode.set(initialDark);

      const savedSize = localStorage.getItem('campus_coin_font_size') as FontSizePreference;
      if (savedSize && ['small', 'medium', 'large'].includes(savedSize)) {
        this.fontSize.set(savedSize);
      }

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

  applyServerPreferences(themePreference: 'LIGHT' | 'DARK' | 'SYSTEM' | null | undefined, fontScale: ServerFontScale | null | undefined): void {
    if (themePreference === 'DARK') {
      this.setDarkMode(true);
    } else if (themePreference === 'LIGHT') {
      this.setDarkMode(false);
    } else if (themePreference === 'SYSTEM') {
      const prefersDark =
        this.isBrowser && typeof window !== 'undefined' && typeof window.matchMedia === 'function'
          ? window.matchMedia('(prefers-color-scheme: dark)').matches
          : false;
      this.setDarkMode(prefersDark);
    }

    const mapped = fontScale ? ThemeService.SERVER_FONT_SCALE[fontScale] : undefined;
    if (mapped) this.setFontSize(mapped);
  }

  get themePreference(): 'LIGHT' | 'DARK' {
    return this.isDarkMode() ? 'DARK' : 'LIGHT';
  }

  private static readonly SERVER_FONT_SCALE: Record<ServerFontScale, FontSizePreference> = {
    SMALL: 'small',
    MEDIUM: 'medium',
    LARGE: 'large',
    XLARGE: 'large'
  };

  private applyTheme(dark: boolean): void {
    if (!this.isBrowser) return;
    const root = document.documentElement;
    if (dark) {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
  }

  private static readonly FONT_SIZE_CLASS: Record<FontSizePreference, string> = {
    small: 'text-size-sm',
    medium: 'text-size-md',
    large: 'text-size-lg'
  };

  private applyFontSize(size: FontSizePreference): void {
    if (!this.isBrowser) return;
    const root = document.documentElement;
    root.classList.remove('text-size-sm', 'text-size-md', 'text-size-lg');
    root.classList.add(ThemeService.FONT_SIZE_CLASS[size] ?? 'text-size-md');
  }
}
