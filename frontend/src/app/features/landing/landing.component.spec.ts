import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LandingComponent } from './landing.component';

describe('LandingComponent', () => {
  let component: LandingComponent;
  let fixture: ComponentFixture<LandingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [provideRouter([])]
    }).compileComponents();

    fixture = TestBed.createComponent(LandingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the landing page component', () => {
    expect(component).toBeTruthy();
  });

  it('should render the navbar, hero, features, how-it-works, social-proof, cta, and footer', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('app-landing-navbar')).toBeTruthy();
    expect(el.querySelector('app-landing-hero')).toBeTruthy();
    expect(el.querySelector('app-landing-features')).toBeTruthy();
    expect(el.querySelector('app-landing-how-it-works')).toBeTruthy();
    expect(el.querySelector('app-landing-social-proof')).toBeTruthy();
    expect(el.querySelector('app-landing-cta')).toBeTruthy();
    expect(el.querySelector('app-landing-footer')).toBeTruthy();
  });

  it('should display the core value proposition in the hero', () => {
    const el: HTMLElement = fixture.nativeElement;
    const heroText = el.querySelector('app-landing-hero')?.textContent;
    expect(heroText).toContain('Smart campus spending');
    expect(heroText).toContain('Effortless student budgeting');
  });
});
