import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ChatbotService } from './chatbot.service';

describe('ChatbotService', () => {
  let service: ChatbotService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [ChatbotService]
    });
    service = TestBed.inject(ChatbotService);
  });

  it('should initialize with welcome message', () => {
    expect(service.messages().length).toBe(1);
    expect(service.messages()[0].sender).toBe('assistant');
  });

  it('should send user message and generate keyword-matched response after delay', () => {
    service.sendMessage('How much did I spend on food?');
    expect(service.messages().length).toBe(2);
    expect(service.messages()[1].text).toBe('How much did I spend on food?');
    expect(service.isTyping()).toBe(true);

    vi.advanceTimersByTime(700);

    expect(service.isTyping()).toBe(false);
    expect(service.messages().length).toBe(3);
    expect(service.messages()[2].sender).toBe('assistant');
    expect(service.messages()[2].text).toContain('Food & Dining');
  });
});
