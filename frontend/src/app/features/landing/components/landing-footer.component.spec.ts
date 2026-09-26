import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LandingFooterComponent } from './landing-footer.component';
import { SavingsJarService } from '../../../core/services/savings-jar.service';
import { MascotService } from '../../../core/services/mascot.service';

describe('LandingFooterComponent (Savings Jar)', () => {
  let component: LandingFooterComponent;
  let fixture: ComponentFixture<LandingFooterComponent>;
  let jarService: SavingsJarService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingFooterComponent],
      providers: [
        SavingsJarService,
        MascotService,
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LandingFooterComponent);
    component = fixture.componentInstance;
    jarService = TestBed.inject(SavingsJarService);
    fixture.detectChanges();
  });

  it('should create the footer component with clear glass savings jar', () => {
    expect(component).toBeTruthy();
    const jarSvg = fixture.nativeElement.querySelector('svg');
    expect(jarSvg).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('SAVINGS');
  });

  it('should have no counter or caption text in footer (Item 2.2)', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).not.toContain('Saved today:');
    expect(el.textContent).not.toContain('Coins landing here automatically tucked into savings');
  });

  it('should dynamically display floating +1 Coin feedback when a coin is caught', () => {
    jarService.recordCoinCaught(0);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    const floatingEl = el.querySelector('.animate-float-fade');
    expect(floatingEl).toBeTruthy();
    expect(floatingEl?.textContent?.trim()).toBe('+1 Coin');
  });
});
