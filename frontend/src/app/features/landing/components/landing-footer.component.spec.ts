import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LandingFooterComponent } from './landing-footer.component';

describe('LandingFooterComponent', () => {
  let component: LandingFooterComponent;
  let fixture: ComponentFixture<LandingFooterComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingFooterComponent],
      providers: [
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LandingFooterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the footer component', () => {
    expect(component).toBeTruthy();
  });

  it('should not contain the savings jar or SAVINGS label', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).not.toContain('SAVINGS');
    expect(el.querySelector('.animate-float-fade')).toBeFalsy();
  });

  it('should render brand information and navigation links', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Campus Coin');
    expect(el.textContent).toContain('Sitemap');
    expect(el.textContent).toContain('Sign In');
  });
});
