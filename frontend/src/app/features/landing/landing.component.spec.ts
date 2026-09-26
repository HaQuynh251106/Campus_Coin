import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ComponentFixture, TestBed, DeferBlockState } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LandingComponent } from './landing.component';
import { MascotService } from '../../core/services/mascot.service';
import { ChatbotService } from '../../core/services/chatbot.service';

describe('LandingComponent', () => {
  let component: LandingComponent;
  let fixture: ComponentFixture<LandingComponent>;
  let mascotService: MascotService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [
        provideRouter([]),
        MascotService,
        ChatbotService
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LandingComponent);
    component = fixture.componentInstance;
    mascotService = TestBed.inject(MascotService);
    fixture.detectChanges();
  });

  it('should create the landing page component', () => {
    expect(component).toBeTruthy();
  });

  it('should render the navbar, hero, features, how-it-works, social-proof, cta, footer, and chatbot-widget', async () => {
    const deferBlocks = await fixture.getDeferBlocks();
    for (const block of deferBlocks) {
      await block.render(DeferBlockState.Complete);
    }
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('app-landing-navbar')).toBeTruthy();
    expect(el.querySelector('app-landing-hero')).toBeTruthy();
    expect(el.querySelector('app-landing-features')).toBeTruthy();
    expect(el.querySelector('app-landing-how-it-works')).toBeTruthy();
    expect(el.querySelector('app-landing-social-proof')).toBeTruthy();
    expect(el.querySelector('app-landing-cta')).toBeTruthy();
    expect(el.querySelector('app-landing-footer')).toBeTruthy();
    expect(el.querySelector('app-chatbot-widget')).toBeTruthy();
  });

  it('should display the core value proposition in the hero', () => {
    const el: HTMLElement = fixture.nativeElement;
    const heroText = el.querySelector('app-landing-hero')?.textContent;
    expect(heroText).toContain('Smart campus spending');
    expect(heroText).toContain('Effortless student budgeting');
  });

  it('should toggle back-to-top button based on scroll threshold (Item 1)', () => {
    expect(component.showBackToTop()).toBe(false);
    expect(fixture.nativeElement.querySelector('button[aria-label="Scroll back to top"]')).toBeNull();

    component.showBackToTop.set(true);
    fixture.detectChanges();

    const topBtn = fixture.nativeElement.querySelector('button[aria-label="Scroll back to top"]');
    expect(topBtn).toBeTruthy();

    const scrollSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    topBtn.click();
    expect(scrollSpy).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' });
    scrollSpy.mockRestore();
  });

  it('should reliably open and reopen chatbot multiple times without disappearing (Item 5)', () => {
    // 1st cycle: open and close
    expect(mascotService.isChatOpen()).toBe(false);
    mascotService.openChat();
    expect(mascotService.isChatOpen()).toBe(true);

    mascotService.closeChat();
    expect(mascotService.isChatOpen()).toBe(false);

    // 2nd cycle: open and close
    mascotService.toggleChat();
    expect(mascotService.isChatOpen()).toBe(true);

    mascotService.closeChat();
    expect(mascotService.isChatOpen()).toBe(false);

    // 3rd cycle: open and close
    mascotService.toggleChat();
    expect(mascotService.isChatOpen()).toBe(true);

    mascotService.closeChat();
    expect(mascotService.isChatOpen()).toBe(false);
  });
});
