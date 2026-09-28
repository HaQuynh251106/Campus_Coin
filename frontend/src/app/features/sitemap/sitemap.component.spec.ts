import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SitemapComponent } from './sitemap.component';
import { ThemeService } from '../../core/services/theme.service';

describe('SitemapComponent', () => {
  let component: SitemapComponent;
  let fixture: ComponentFixture<SitemapComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SitemapComponent],
      providers: [
        provideRouter([]),
        ThemeService
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SitemapComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the sitemap component', () => {
    expect(component).toBeTruthy();
  });

  it('should render page title and SRS requirement badge', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Campus Coin — Sitemap');
    expect(el.textContent).toContain('SRS Deliverable Requirement');
  });

  it('should have 4 architecture sections matching specification', () => {
    expect(component.sections.length).toBe(4);
    const titles = component.sections.map(s => s.title);
    expect(titles).toEqual(['Public', 'Authentication', 'Student Portal', 'Admin Portal']);
  });

  it('should list all active application routes across sections', () => {
    const allPaths = component.sections.flatMap(s => s.routes.map(r => r.path));

    // Public routes
    expect(allPaths).toContain('/');
    expect(allPaths).toContain('/sitemap');

    // Auth routes
    expect(allPaths).toContain('/auth/login');
    expect(allPaths).toContain('/auth/register');
    expect(allPaths).toContain('/auth/forgot-password');

    // Student Portal routes
    expect(allPaths).toContain('/app/home');
    expect(allPaths).toContain('/app/quick-add');
    expect(allPaths).toContain('/app/reports');
    expect(allPaths).toContain('/app/budgets');
    expect(allPaths).toContain('/app/categories');
    expect(allPaths).toContain('/app/recurring');
    expect(allPaths).toContain('/app/tips');
    expect(allPaths).toContain('/app/bookmarks');
    expect(allPaths).toContain('/app/profile');
    expect(allPaths).toContain('/app/imports');
    expect(allPaths).toContain('/app/insights');
    expect(allPaths).toContain('/app/anomalies');
    expect(allPaths).toContain('/app/forecast');
    expect(allPaths).toContain('/app/recent-activity');

    // Admin Portal routes
    expect(allPaths).toContain('/admin/dashboard');
    expect(allPaths).toContain('/admin/users');
    expect(allPaths).toContain('/admin/categories');

    expect(allPaths.length).toBe(22);
  });

  it('should distinguish public routes from protected routes with appropriate badges', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Public Access');
    expect(el.textContent).toContain('Requires student login');
    expect(el.textContent).toContain('Requires admin login');
  });

  it('should render the preformatted plain text hierarchy tree', () => {
    const el: HTMLElement = fixture.nativeElement;
    const pre = el.querySelector('pre');
    expect(pre).toBeTruthy();
    expect(pre?.textContent).toContain('Campus Coin — Sitemap');
    expect(pre?.textContent).toContain('Public');
    expect(pre?.textContent).toContain('├── / — Guest Landing Page');
    expect(pre?.textContent).toContain('└── /sitemap — This page');
    expect(pre?.textContent).toContain('Student Portal (requires student login)');
    expect(pre?.textContent).toContain('Admin Portal (requires admin login)');
  });
});
