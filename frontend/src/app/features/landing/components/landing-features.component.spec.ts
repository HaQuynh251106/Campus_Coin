import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LandingFeaturesComponent } from './landing-features.component';

describe('LandingFeaturesComponent', () => {
  let component: LandingFeaturesComponent;
  let fixture: ComponentFixture<LandingFeaturesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingFeaturesComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(LandingFeaturesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the landing features component', () => {
    expect(component).toBeTruthy();
  });

  it('should showcase all 7 AI capabilities in order', () => {
    expect(component.aiFeatures.length).toBe(7);

    const ids = component.aiFeatures.map(f => f.id);
    expect(ids).toEqual([
      'chat-assistant',
      'smart-categorization',
      'smart-csv-import',
      'monthly-insights',
      'unusual-activity',
      'forecast',
      'saving-tips'
    ]);
  });

  it('should render headers and all 7 feature titles in the template', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('AI-Powered Financial Assistant');
    expect(el.textContent).toContain('Smart financial intelligence, built for student life');

    // 7 capabilities rendered
    expect(el.textContent).toContain('Talk to Your Money in Plain English');
    expect(el.textContent).toContain('Instant Categorization as You Type');
    expect(el.textContent).toContain('Bulk Bank Statement Import with Auto-Tagging');
    expect(el.textContent).toContain('Friendly, Plain-Language Month-End Recaps');
    expect(el.textContent).toContain('Automatic Outlier & Duplicate Charge Detection');
    expect(el.textContent).toContain("Predict Next Month's Expenses Before They Arrive");
    expect(el.textContent).toContain('Habit-Driven Tips Tailored to Real Student Life');
  });

  it('should render the reliability and fallback trust message', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Dependable & Reliable by Design');
    expect(el.textContent).toContain(
      'Every AI feature has a reliable built-in fallback, so your budgeting never breaks even if AI assistance is temporarily unavailable.'
    );
    expect(el.textContent).toContain('Guaranteed Continuity');
  });
});
