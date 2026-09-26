import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ChatbotWidgetComponent } from './chatbot-widget.component';
import { ChatbotService } from '../../../core/services/chatbot.service';
import { MascotService } from '../../../core/services/mascot.service';

describe('ChatbotWidgetComponent', () => {
  let component: ChatbotWidgetComponent;
  let fixture: ComponentFixture<ChatbotWidgetComponent>;
  let chatbotService: ChatbotService;
  let mascotService: MascotService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatbotWidgetComponent],
      providers: [
        ChatbotService,
        MascotService,
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatbotWidgetComponent);
    component = fixture.componentInstance;
    chatbotService = TestBed.inject(ChatbotService);
    mascotService = TestBed.inject(MascotService);
    fixture.detectChanges();
  });

  it('should create the chatbot widget component with squirrel mascot', () => {
    expect(component).toBeTruthy();
    expect(fixture.nativeElement.querySelector('app-squirrel-mascot')).toBeTruthy();
  });

  it('should toggle chat window open and closed via mascotService', () => {
    expect(component.isOpen()).toBe(false);
    component.toggleOpen();
    expect(component.isOpen()).toBe(true);
    component.closeChat();
    expect(component.isOpen()).toBe(false);
  });

  it('should send quick prompt on button click', () => {
    component.sendQuickPrompt('What is my budget status?');
    expect(chatbotService.messages().some(m => m.text === 'What is my budget status?')).toBe(true);
  });

  it('should apply custom thin scrollbar styling to message scroll container (A.1)', () => {
    component.toggleOpen();
    fixture.detectChanges();

    const scrollContainer = fixture.nativeElement.querySelector('.chat-scrollbar');
    expect(scrollContainer).toBeTruthy();
  });

  it('should render mascot avatar next to bot assistant messages (A.4)', () => {
    component.toggleOpen();
    fixture.detectChanges();

    const botAvatars = fixture.nativeElement.querySelectorAll('app-icon[name="squirrel-logo"]');
    // Header icon + at least 1 assistant welcome message avatar
    expect(botAvatars.length).toBeGreaterThanOrEqual(2);
  });

  it('should provide accessible placeholder and active state on send button (A.3)', () => {
    component.toggleOpen();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('input[name="chatInput"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input.placeholder).toBe('Ask about budgets, meals, allowance…');

    const sendBtn = fixture.nativeElement.querySelector('button[title="Send message"]') as HTMLButtonElement;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(true);

    component.userInput.set('Hello assistant');
    fixture.detectChanges();

    expect(sendBtn.disabled).toBe(false);
  });
});
