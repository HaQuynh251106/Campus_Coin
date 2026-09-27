import { Injectable, signal, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { FontSizePreference, ServerFontScale } from '../models/user.model';

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
      const prefersDark =
        typeof window !== 'undefined' && typeof window.matchMedia === 'function'
          ? window.matchMedia('(prefers-color-scheme: dark)').matches
          : false;
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

  /**
   * Applies the preferences the server holds for this account (UC-27).
   *
   * The server is the authority once someone is signed in: the local values are only a cache for
   * the sign-in screen and the first paint, and they are per-browser rather than per-account, so
   * two students sharing a machine would otherwise inherit each other's choice. This overwrites
   * that cache with what the account actually holds.
   *
   * `themePreference` of `SYSTEM` follows the operating system, which is what the constructor reads
   * when nothing has been stored.
   */
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

  /** The preference the server currently holds for this browser's theme, for the PATCH body. */
  get themePreference(): 'LIGHT' | 'DARK' {
    return this.isDarkMode() ? 'DARK' : 'LIGHT';
  }

  /**
   * The stylesheet defines three text sizes (`html.text-size-sm` / `-md` / `-lg`), but UC-27 accepts
   * four (`SMALL` … `XLARGE`). There is no fourth rule to map `XLARGE` onto, so it takes the largest
   * size that exists rather than a size that does nothing — the same class-mapping problem the
   * `FONT_SIZE_CLASS` table below already had to solve. Adding a fourth rule would change the
   * design tokens, which is not this integration's to do.
   */
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

  // The stylesheet (_tokens.scss) defines html.text-size-sm / -md / -lg, so the
  // preference has to be translated to that short suffix. Adding the raw preference
  // ("text-size-small") matched no rule and left the text size unchanged.
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
