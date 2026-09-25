import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MascotService } from './mascot.service';

describe('MascotService', () => {
  let service: MascotService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        MascotService,
        provideRouter([])
      ]
    });
    service = TestBed.inject(MascotService);
  });

  it('should initialize with idle state', () => {
    expect(service.currentState()).toBe('idle');
  });

  it('should trigger state and speech bubble text', () => {
    service.triggerState('deposit', 'Got it, tucked that one away!', 2000);
    expect(service.currentState()).toBe('deposit');
    expect(service.bubbleText()).toBe('Got it, tucked that one away!');
  });

  it('should toggle chat state and update mascot to chat-open', () => {
    expect(service.isChatOpen()).toBe(false);
    service.toggleChat();
    expect(service.isChatOpen()).toBe(true);
    expect(service.currentState()).toBe('chat-open');

    service.closeChat();
    expect(service.isChatOpen()).toBe(false);
    expect(service.currentState()).toBe('idle');
  });

  it('should trigger guest roaming prompts', () => {
    service.startGuestRoaming();
    expect(service.currentState()).toBe('guest-roaming');
    expect(service.bubbleText()).toBeTruthy();
  });
});
