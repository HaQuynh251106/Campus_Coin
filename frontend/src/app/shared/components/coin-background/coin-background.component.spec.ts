import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CoinBackgroundComponent } from './coin-background.component';
import { SavingsJarService } from '../../../core/services/savings-jar.service';
import { MascotService } from '../../../core/services/mascot.service';

describe('CoinBackgroundComponent', () => {
  let component: CoinBackgroundComponent;
  let fixture: ComponentFixture<CoinBackgroundComponent>;
  let jarService: SavingsJarService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CoinBackgroundComponent],
      providers: [
        SavingsJarService,
        MascotService,
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(CoinBackgroundComponent);
    component = fixture.componentInstance;
    jarService = TestBed.inject(SavingsJarService);
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

  it('should inject SavingsJarService for automatic footer coin-catching', () => {
    expect(jarService).toBeTruthy();
    expect(jarService.savedCount()).toBe(0);
  });
});
