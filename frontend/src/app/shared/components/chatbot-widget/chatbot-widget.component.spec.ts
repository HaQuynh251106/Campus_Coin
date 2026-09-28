import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChatbotWidgetComponent } from './chatbot-widget.component';
import { ChatbotService } from '../../../core/services/chatbot.service';
import { MascotService } from '../../../core/services/mascot.service';

describe('ChatbotWidgetComponent', () => {
  let component: ChatbotWidgetComponent;
  let fixture: ComponentFixture<ChatbotWidgetComponent>;
  let chatbotService: ChatbotService;
  let mascotService: MascotService;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatbotWidgetComponent],
      providers: [
        ChatbotService,
        MascotService,

        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatbotWidgetComponent);
    component = fixture.componentInstance;
    chatbotService = TestBed.inject(ChatbotService);
    mascotService = TestBed.inject(MascotService);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {

    httpMock.match(() => true).forEach(req =>
      req.request.method === 'GET'
        ? req.flush({ available: true, model: 'gemini-3.5-flash' })
        : req.flush({ reply: 'ok', model: 'gemini-3.5-flash', toolsUsed: [] })
    );
    httpMock.verify();
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

  it('should send quick prompt on button click in student mode', () => {
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

    expect(botAvatars.length).toBeGreaterThanOrEqual(2);
  });

  it('should provide accessible placeholder and active state on send button (A.3)', () => {
    component.toggleOpen();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('textarea[name="chatInput"]') as HTMLTextAreaElement;
    expect(input).toBeTruthy();
    expect(input.placeholder).toBe('Ask about budgets, meals, allowance…');

    const sendBtn = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(true);

    component.userInput.set('Hello assistant');
    fixture.detectChanges();

    expect(sendBtn.disabled).toBe(false);
  });

  it('should immediately clear typed text in textarea and reset userInput on send', () => {
    component.toggleOpen();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('textarea[name="chatInput"]') as HTMLTextAreaElement;
    input.value = 'How much did I spend on groceries?';
    component.userInput.set('How much did I spend on groceries?');
    fixture.detectChanges();

    component.onSend();
    fixture.detectChanges();

    expect(component.userInput()).toBe('');
    expect(input.value).toBe('');
  });

  it('should keep textarea enabled while assistant is replying so user can compose next message in advance', () => {
    component.toggleOpen();
    chatbotService.isTyping.set(true);
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('textarea[name="chatInput"]') as HTMLTextAreaElement;
    expect(input).toBeTruthy();
    expect(input.disabled).toBe(false);

    input.value = 'Can I spend money on coffee today?';
    component.userInput.set('Can I spend money on coffee today?');
    fixture.detectChanges();

    const sendBtn = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(true);
    expect(sendBtn.getAttribute('title')).toBe('Assistant is responding…');

    chatbotService.isTyping.set(false);
    fixture.detectChanges();

    expect(sendBtn.disabled).toBe(false);
    expect(input.value).toBe('Can I spend money on coffee today?');

    component.onSend();
    fixture.detectChanges();

    expect(component.userInput()).toBe('');
    expect(input.value).toBe('');
  });

  it('asks the backend about availability once when a student view is mounted', () => {

    const requests = httpMock.match('/api/v1/chat');
    expect(requests.length).toBe(1);
    expect(requests[0].request.method).toBe('GET');
    requests[0].flush({ available: true, model: 'gemini-3.5-flash' });
  });

  it('renders a multiline assistant reply with its line breaks preserved', () => {
    component.toggleOpen();
    chatbotService.messages.set([
      {
        id: 'msg-assistant-1',
        sender: 'assistant',
        text: 'Food: 84.20\nTransport: 30.00',
        time: '09:41'
      }
    ]);
    fixture.detectChanges();

    const bubble = fixture.nativeElement.querySelector('.whitespace-pre-wrap') as HTMLElement;
    expect(bubble).toBeTruthy();

    expect(bubble.textContent).toContain('Food: 84.20\nTransport: 30.00');
  });

  it('offers a retry on a failed turn and re-sends the question it could not answer', () => {
    component.toggleOpen();
    chatbotService.messages.set([
      {
        id: 'msg-assistant-1',
        sender: 'assistant',
        text: "I couldn't reach the assistant just now.",
        time: '09:41',
        failed: true,
        retryText: 'How much did I spend this month?'
      }
    ]);
    fixture.detectChanges();

    const retryBtn = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>
    ).find(b => b.textContent?.trim() === 'Retry');
    expect(retryBtn).toBeTruthy();

    retryBtn!.click();

    const req = httpMock.expectOne(
      r => r.url === '/api/v1/chat' && r.method === 'POST'
    );
    expect((req.request.body as { message: string }).message).toBe('How much did I spend this month?');
    req.flush({ reply: 'You spent 271.50.', model: 'gemini-3.5-flash', toolsUsed: [] });
  });

  describe('Guest Gated Mode (Landing Page)', () => {
    beforeEach(() => {
      component.isGuestMode = true;
      fixture.detectChanges();
    });

    it('asks the backend nothing when mounted as the guest panel', () => {

      httpMock.match('/api/v1/chat').forEach(req => req.flush({ available: true, model: 'gemini-3.5-flash' }));

      const guestFixture = TestBed.createComponent(ChatbotWidgetComponent);
      guestFixture.componentInstance.isGuestMode = true;
      guestFixture.detectChanges();

      expect(httpMock.match('/api/v1/chat').length).toBe(0);
    });

    it('should render gated panel without message input or quick prompts when opened', () => {
      component.toggleOpen();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;

      expect(el.textContent).toContain('Log in first to chat with Sooc!');
      expect(el.textContent).toContain('Sign in with your campus account');

      const loginLink = el.querySelector('a[href="/auth/login"], a[routerLink="/auth/login"]');
      const signupLink = el.querySelector('a[href="/auth/register"], a[routerLink="/auth/register"]');
      expect(loginLink).toBeTruthy();
      expect(signupLink).toBeTruthy();

      expect(el.querySelector('textarea[name="chatInput"]')).toBeNull();
      expect(el.querySelector('.chat-scrollbar')).toBeNull();
    });

    it('should close gated panel when close button is clicked and restore mascot', () => {
      component.toggleOpen();
      fixture.detectChanges();
      expect(component.isOpen()).toBe(true);

      const closeBtn = fixture.nativeElement.querySelector('button[aria-label="Close panel"]') as HTMLButtonElement;
      expect(closeBtn).toBeTruthy();
      closeBtn.click();
      fixture.detectChanges();

      expect(component.isOpen()).toBe(false);
      expect(mascotService.isChatOpen()).toBe(false);
    });

    it('should close gated panel when Escape key is pressed', () => {
      component.toggleOpen();
      fixture.detectChanges();
      expect(component.isOpen()).toBe(true);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();

      expect(component.isOpen()).toBe(false);
    });

    it('should apply dark mode classes to gated panel container, header and buttons (Item 1)', () => {
      component.toggleOpen();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      const panel = el.querySelector('.animate-scale-up') as HTMLElement;
      expect(panel).toBeTruthy();
      expect(panel.classList.contains('dark:bg-neutral-900')).toBe(true);
      expect(panel.classList.contains('dark:border-neutral-700')).toBe(true);

      const header = panel.querySelector('.border-b') as HTMLElement;
      expect(header.classList.contains('dark:bg-neutral-800')).toBe(true);
      expect(header.classList.contains('dark:border-neutral-700')).toBe(true);
    });
  });
});
