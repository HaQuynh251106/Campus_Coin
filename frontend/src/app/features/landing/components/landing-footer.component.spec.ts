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

  it('should create the footer component with savings jar', () => {
    expect(component).toBeTruthy();
    const jarSvg = fixture.nativeElement.querySelector('svg');
    expect(jarSvg).toBeTruthy();
  });

  it('should display the initial 0 coins saved count', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Saved today: 0 coins');
  });

  it('should dynamically update the running counter when coins are caught', () => {
    jarService.recordCoinCaught(0);
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Saved today: 1 coin');
    expect(el.querySelector('.animate-float-fade')).toBeTruthy();
  });
});
