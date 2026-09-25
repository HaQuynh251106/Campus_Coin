import { Injectable, signal, inject, PLATFORM_ID, NgZone } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';

export type MascotState =
  | 'idle'
  | 'walk'
  | 'deposit'
  | 'coin-flip'
  | 'analyzing'
  | 'sleepy'
  | 'guest-roaming'
  | 'chat-open';

export type MascotAnchor =
  | 'bottom-right'
  | 'bottom-left'
  | 'mid-right'
  | 'mid-left';

export interface AnchorCoord {
  id: MascotAnchor;
  name: string;
  xPercent: number; // viewport percentage from left
  yPercent: number; // viewport percentage from top
  cssStyle: { top?: string; bottom?: string; left?: string; right?: string };
}

@Injectable({
  providedIn: 'root'
})
export class MascotService {
  private router = inject(Router);
  private ngZone = inject(NgZone);
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  // Reactive State Signals
  readonly currentState = signal<MascotState>('idle');
  readonly bubbleText = signal<string | null>(null);
  readonly isChatOpen = signal<boolean>(false);
  readonly currentAnchor = signal<MascotAnchor>('bottom-right');

  // Timers
  private stateResetTimer?: any;
  private bubbleResetTimer?: any;
  private inactivityTimer?: any;
  private roamingIntervalTimer?: any;

  // Guest Roaming Prompts for Landing Page
  private readonly guestPrompts = [
    "Log in and I'll help you manage your money!",
    'Click me to try the AI assistant!',
    'Track your campus allowance in seconds!',
    'Zero bank credentials required!'
  ];
  private guestPromptIndex = 0;

  constructor() {
    if (this.isBrowser) {
      this.initRouterListener();
      this.initInactivityDetector();
    }
  }

  // --- State Triggers ---

  triggerState(state: MascotState, bubble?: string, durationMs = 3500): void {
    if (this.isChatOpen() && state !== 'chat-open') {
      // Don't interrupt open chat with background idle/walk
      return;
    }

    this.clearTimers();
    this.currentState.set(state);

    if (bubble) {
      this.bubbleText.set(bubble);
    } else {
      this.bubbleText.set(null);
    }

    if (durationMs > 0 && state !== 'idle' && state !== 'chat-open' && state !== 'guest-roaming') {
      this.stateResetTimer = setTimeout(() => {
        this.currentState.set('idle');
        this.bubbleText.set(null);
      }, durationMs);
    }
  }

  toggleChat(): void {
    const next = !this.isChatOpen();
    this.isChatOpen.set(next);

    if (next) {
      this.triggerState('chat-open', 'How can I help you today?', 0);
    } else {
      this.triggerState('idle', undefined, 0);
    }
  }

  closeChat(): void {
    this.isChatOpen.set(false);
    this.triggerState('idle', undefined, 0);
  }

  openChat(): void {
    this.isChatOpen.set(true);
    this.triggerState('chat-open', 'How can I help you today?', 0);
  }

  // Specific Business Event Hooks
  onTransactionLogged(): void {
    this.triggerState('deposit', 'Got it, tucked that one away!', 4000);
  }

  onQuickAddTyping(): void {
    // 30% chance to flip coin when typing to avoid spamming
    if (Math.random() < 0.35 && this.currentState() === 'idle') {
      this.triggerState('coin-flip', 'Thinking it over?', 3000);
    }
  }

  onAnalyzing(): void {
    this.triggerState('analyzing', "Heads up — this category's getting close to its limit.", 4000);
  }

  startGuestRoaming(): void {
    this.currentState.set('guest-roaming');
    this.cycleGuestPrompt();

    if (this.isBrowser) {
      clearInterval(this.roamingIntervalTimer);
      this.roamingIntervalTimer = setInterval(() => {
        if (this.currentState() === 'guest-roaming') {
          this.cycleGuestPrompt();
        }
      }, 7000);
    }
  }

  private cycleGuestPrompt(): void {
    const prompt = this.guestPrompts[this.guestPromptIndex % this.guestPrompts.length];
    this.guestPromptIndex++;
    this.bubbleText.set(prompt);
  }

  // Router listener for Walk/Hop transitions
  private initRouterListener(): void {
    this.router.events
      .pipe(filter(event => event instanceof NavigationEnd))
      .subscribe(event => {
        const url = (event as NavigationEnd).urlAfterRedirects || (event as NavigationEnd).url;

        if (url === '/' || url === '') {
          this.startGuestRoaming();
          return;
        }

        // On student routes: Walk / hop transition with contextual quote
        if (url.includes('/reports')) {
          this.triggerState('walk', "Let's check your reports!", 3200);
        } else if (url.includes('/budgets')) {
          this.triggerState('walk', 'Checking monthly budget caps!', 3200);
        } else if (url.includes('/quick-add')) {
          this.triggerState('walk', 'Ready to log spending!', 3200);
        } else if (url.includes('/home')) {
          this.triggerState('walk', 'Heading back to your feed!', 3000);
        } else {
          this.triggerState('idle', undefined, 0);
        }
      });
  }

  // Inactivity Detector (Sleepy state after 90 seconds of no user interaction)
  private initInactivityDetector(): void {
    const resetInactivity = () => {
      if (this.currentState() === 'sleepy') {
        this.triggerState('idle', 'Awake and ready!', 2000);
      }
      clearTimeout(this.inactivityTimer);
      this.inactivityTimer = setTimeout(() => {
        if (!this.isChatOpen() && this.currentState() !== 'guest-roaming') {
          this.triggerState('sleepy', "I'll nap here — click me if you need anything!", 0);
        }
      }, 90000); // 90 seconds
    };

    const events = ['mousemove', 'keydown', 'scroll', 'touchstart'];
    events.forEach(ev => window.addEventListener(ev, resetInactivity, { passive: true }));
    resetInactivity();
  }

  private clearTimers(): void {
    clearTimeout(this.stateResetTimer);
    clearTimeout(this.bubbleResetTimer);
  }
}
