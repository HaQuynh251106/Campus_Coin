import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CategoryTagComponent } from './category-tag.component';

describe('CategoryTagComponent', () => {
  let component: CategoryTagComponent;
  let fixture: ComponentFixture<CategoryTagComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CategoryTagComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CategoryTagComponent);
    component = fixture.componentInstance;
  });

  it('should create the category tag component', () => {
    expect(component).toBeTruthy();
  });

  it('should map "Food & Dining" to the curated orange hue, not gold', () => {
    component.name = 'Food & Dining';
    const styles = component.styles;
    expect(styles.dark.text).toBe('#FB923C');
    expect(styles.dark.bg).toBe('rgba(234, 88, 12, 0.15)');
    expect(styles.dark.border).toBe('rgba(234, 88, 12, 0.35)');
  });

  it('should map "Coffee & Snacks" to the curated teal hue', () => {
    component.name = 'Coffee & Snacks';
    const styles = component.styles;
    expect(styles.dark.text).toBe('#2DD4BF');
    expect(styles.dark.bg).toBe('rgba(13, 148, 136, 0.15)');
  });

  it('should guard against accidental gold/yellow hex and remap to a curated categorical hue', () => {
    component.color = '#FFE600';
    const styles = component.styles;
    expect(styles.dark.text).not.toBe('#FFE600');
    expect(styles.dark.text).toBe('#FB923C');
  });

  it('should render icon and name', () => {
    component.name = 'Books & Supplies';
    component.icon = 'book-open';
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Books & Supplies');
    expect(element.querySelector('app-icon')).toBeTruthy();
  });
});
