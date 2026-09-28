import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AvatarComponent, DEFAULT_AVATAR_URL, resolveAvatarUrl } from './avatar.component';

describe('AvatarComponent', () => {
  let component: AvatarComponent;
  let fixture: ComponentFixture<AvatarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AvatarComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(AvatarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the avatar component', () => {
    expect(component).toBeTruthy();
  });

  it('should default to squirrel mascot avatar when no avatarUrl is provided', () => {
    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.src).toContain(DEFAULT_AVATAR_URL);
  });

  it('should discard legacy Unsplash person photos and fall back to squirrel mascot', () => {
    component.avatarUrl = 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80';
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.src).toContain(DEFAULT_AVATAR_URL);
  });

  it('should render custom uploaded avatar URL when valid', () => {
    const customUrl = 'https://cdn.campuscoin.edu/avatars/student-123.png';
    component.avatarUrl = customUrl;
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.src).toBe(customUrl);
  });

  it('should fall back to squirrel mascot on image load error', () => {
    component.avatarUrl = 'https://example.com/broken-image.jpg';
    fixture.detectChanges();

    component.handleImageError();
    fixture.detectChanges();

    const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
    expect(img.src).toContain(DEFAULT_AVATAR_URL);
  });

  it('should apply appropriate classes for size sm (32px), md (36px), lg (56px)', () => {
    component.size = 'sm';
    fixture.detectChanges();
    expect(component.containerClasses()).toContain('w-8 h-8');

    component.size = 'md';
    fixture.detectChanges();
    expect(component.containerClasses()).toContain('w-9 h-9');

    component.size = 'lg';
    fixture.detectChanges();
    expect(component.containerClasses()).toContain('w-14 h-14');
  });

  describe('resolveAvatarUrl helper', () => {
    it('should return default avatar for null or empty strings', () => {
      expect(resolveAvatarUrl(null)).toBe(DEFAULT_AVATAR_URL);
      expect(resolveAvatarUrl(undefined)).toBe(DEFAULT_AVATAR_URL);
      expect(resolveAvatarUrl('')).toBe(DEFAULT_AVATAR_URL);
      expect(resolveAvatarUrl('   ')).toBe(DEFAULT_AVATAR_URL);
    });

    it('should reject Unsplash photos of real people', () => {
      expect(resolveAvatarUrl('https://images.unsplash.com/photo-12345')).toBe(DEFAULT_AVATAR_URL);
    });

    it('should keep custom uploaded avatars', () => {
      expect(resolveAvatarUrl('https://my-bucket.s3.amazonaws.com/avatar.png')).toBe('https://my-bucket.s3.amazonaws.com/avatar.png');
    });
  });
});
