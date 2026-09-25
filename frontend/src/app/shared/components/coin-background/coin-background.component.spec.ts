import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CoinBackgroundComponent } from './coin-background.component';

describe('CoinBackgroundComponent', () => {
  let component: CoinBackgroundComponent;
  let fixture: ComponentFixture<CoinBackgroundComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CoinBackgroundComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CoinBackgroundComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    fixture.destroy();
  });

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  it('should render a canvas element with pointer-events-none', () => {
    fixture.detectChanges();
    const canvas = fixture.nativeElement.querySelector('canvas');
    expect(canvas).toBeTruthy();
    expect(canvas.classList.contains('pointer-events-none')).toBe(true);
  });
});
