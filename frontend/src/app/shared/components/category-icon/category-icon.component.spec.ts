import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CategoryIconComponent } from './category-icon.component';

describe('CategoryIconComponent', () => {
  let component: CategoryIconComponent;
  let fixture: ComponentFixture<CategoryIconComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CategoryIconComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CategoryIconComponent);
    component = fixture.componentInstance;
  });

  it('should create the category icon component', () => {
    expect(component).toBeTruthy();
  });

  it('should map "Food & Dining" to the curated orange hue', () => {
    component.name = 'Food & Dining';
    const hue = component.hue;
    expect(hue.text).toBe('#EA580C');
    expect(hue.bgDark).toBe('rgba(234, 88, 12, 0.15)');
  });

  it('should map "Coffee & Snacks" to the curated teal hue', () => {
    component.name = 'Coffee & Snacks';
    const hue = component.hue;
    expect(hue.text).toBe('#0D9488');
    expect(hue.bgDark).toBe('rgba(13, 148, 136, 0.15)');
  });

  it('should render icon inside without any text inside container', () => {
    component.name = 'Books & Supplies';
    component.icon = 'book-open';
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent?.trim()).toBe('');
    expect(element.querySelector('app-icon')).toBeTruthy();
  });
});
