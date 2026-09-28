import {
  Component,
  Input,
  computed,
  signal,
  ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Shared default avatar constant referencing the mascot SVG on a soft gold/cream circular base.
 */
export const DEFAULT_AVATAR_URL = '/assets/images/default-avatar.svg';

/**
 * Sanitizes an avatar URL: rejects null/undefined, empty strings, and legacy stock person photos.
 */
export function resolveAvatarUrl(url?: string | null): string {
  if (!url || typeof url !== 'string' || url.trim() === '') {
    return DEFAULT_AVATAR_URL;
  }
  // Discard any legacy stock photos of real people
  if (url.includes('images.unsplash.com')) {
    return DEFAULT_AVATAR_URL;
  }
  return url;
}

@Component({
  selector: 'app-avatar',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="rounded-full overflow-hidden shrink-0 flex items-center justify-center select-none"
      [ngClass]="containerClasses()"
    >
      <img
        [src]="displayUrl()"
        [alt]="resolvedAlt()"
        (error)="handleImageError()"
        loading="lazy"
        class="w-full h-full object-cover transition-transform duration-200"
      />
    </div>
  `
})
export class AvatarComponent {
  private _avatarUrl = signal<string | null | undefined>(null);
  private _size = signal<'sm' | 'md' | 'lg' | 'xl' | number>('md');
  private _showBorder = signal<boolean>(true);
  private _className = signal<string>('');
  private hasError = signal<boolean>(false);

  @Input()
  set avatarUrl(val: string | null | undefined) {
    this._avatarUrl.set(val);
    this.hasError.set(false);
  }
  get avatarUrl(): string | null | undefined {
    return this._avatarUrl();
  }

  @Input() name?: string | null;
  @Input() alt?: string;

  @Input()
  set size(val: 'sm' | 'md' | 'lg' | 'xl' | number) {
    this._size.set(val);
  }
  get size(): 'sm' | 'md' | 'lg' | 'xl' | number {
    return this._size();
  }

  @Input()
  set className(val: string) {
    this._className.set(val || '');
  }
  get className(): string {
    return this._className();
  }

  @Input()
  set showBorder(val: boolean) {
    this._showBorder.set(val);
  }
  get showBorder(): boolean {
    return this._showBorder();
  }

  readonly displayUrl = computed(() => {
    if (this.hasError()) {
      return DEFAULT_AVATAR_URL;
    }
    return resolveAvatarUrl(this._avatarUrl());
  });

  readonly resolvedAlt = computed(() => {
    if (this.alt) return this.alt;
    if (this.name) return `${this.name}'s Avatar`;
    return 'Campus Coin Squirrel Mascot Avatar';
  });

  readonly containerClasses = computed(() => {
    const classes: string[] = [];
    const s = this._size();

    // Size mappings
    if (typeof s === 'number') {
      classes.push(`w-[${s}px]`, `h-[${s}px]`);
    } else {
      switch (s) {
        case 'sm':
          classes.push('w-8', 'h-8'); // 32px (Header top bar)
          break;
        case 'lg':
          classes.push('w-14', 'h-14'); // 56px (Profile page)
          break;
        case 'xl':
          classes.push('w-20', 'h-20'); // 80px
          break;
        case 'md':
        default:
          classes.push('w-9', 'h-9'); // 36px (Sidebar user block)
          break;
      }
    }

    if (this._showBorder()) {
      classes.push('border', 'border-amber-200', 'dark:border-amber-900/60');
    }

    classes.push('bg-amber-50', 'dark:bg-neutral-800');

    if (this._className()) {
      classes.push(this._className());
    }

    return classes.join(' ');
  });

  handleImageError(): void {
    this.hasError.set(true);
  }
}
