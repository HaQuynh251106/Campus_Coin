import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SquirrelMascotComponent } from './squirrel-mascot.component';
import { MascotService } from '../../../core/services/mascot.service';

describe('SquirrelMascotComponent', () => {
  let component: SquirrelMascotComponent;
  let fixture: ComponentFixture<SquirrelMascotComponent>;
  let mascotService: MascotService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SquirrelMascotComponent],
      providers: [
        MascotService,
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SquirrelMascotComponent);
    component = fixture.componentInstance;
    mascotService = TestBed.inject(MascotService);
    fixture.detectChanges();
  });

  it('should create the mascot component', () => {
    expect(component).toBeTruthy();
  });

  it('should display the squirrel SVG with high-contrast outlines', () => {
    const svg = fixture.nativeElement.querySelector('svg');
    expect(svg).toBeTruthy();
    const tailPath = svg.querySelector('path[stroke="#92400E"]');
    expect(tailPath).toBeTruthy();
  });

  it('should render speech bubble when bubbleText is present', () => {
    mascotService.bubbleText.set('Hello student!');
    fixture.detectChanges();

    const bubble = fixture.nativeElement.querySelector('.shadow-subtle-lg');
    expect(bubble).toBeTruthy();
    expect(bubble.textContent).toContain('Hello student!');
  });

  it('should dismiss greeting bubble without opening chat (B.4)', () => {
    mascotService.bubbleText.set("Need help figuring out this month's budget?");
    fixture.detectChanges();

    const dismissBtn = fixture.nativeElement.querySelector('button[aria-label="Dismiss greeting"]');
    expect(dismissBtn).toBeTruthy();

    dismissBtn.click();
    fixture.detectChanges();

    expect(mascotService.bubbleText()).toBeNull();
    expect(mascotService.isChatOpen()).toBe(false);
  });

  it('should toggle chat on mascot button click', () => {
    expect(mascotService.isChatOpen()).toBe(false);
    const button = fixture.nativeElement.querySelector('button');
    button.click();
    expect(mascotService.isChatOpen()).toBe(true);
  });

  it('should display visual AI cue sparkle badge opposite to green online dot (B.1)', () => {
    const onlineDot = fixture.nativeElement.querySelector('span[title="AI Assistant Online"]');
    const aiSparkle = fixture.nativeElement.querySelector('span[title="AI Powered Assistant"]');
    expect(onlineDot).toBeTruthy();
    expect(aiSparkle).toBeTruthy();
  });

  it('should render desktop hover tooltip when no bubble is active (B.3)', () => {
    mascotService.bubbleText.set(null);
    fixture.detectChanges();

    const tooltip = fixture.nativeElement.querySelector('.hidden.sm\\:block');
    expect(tooltip).toBeTruthy();
    expect(tooltip.textContent).toContain('Ask the Campus Coin assistant');
  });

  it('should hide mascot launcher container when chat panel is open (A.2)', () => {
    mascotService.isChatOpen.set(true);
    fixture.detectChanges();

    const wrapper = fixture.nativeElement.querySelector('div');
    expect(wrapper.classList.contains('invisible')).toBe(true);
    expect(wrapper.classList.contains('opacity-0')).toBe(true);
  });
});
