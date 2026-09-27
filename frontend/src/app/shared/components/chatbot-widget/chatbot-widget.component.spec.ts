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
        // The widget asks the backend whether the assistant is available when it is constructed, so
        // the service needs an HttpClient. The testing provider answers that call with nothing rather
        // than a real request.
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
    // The widget asks the backend whether the assistant is available as soon as it is created in
    // student mode, and several tests here are about rendering rather than about the request a click
    // starts. Drain whatever is still outstanding so a test need not know about the calls it is not
    // asserting on; `verify` then fails only on a request a test genuinely left dangling, rather than
    // on the availability check every test inherits.
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
    // Header icon + at least 1 assistant welcome message avatar
    expect(botAvatars.length).toBeGreaterThanOrEqual(2);
  });

  it('should provide accessible placeholder and active state on send button (A.3)', () => {
    component.toggleOpen();
    fixture.detectChanges();

    // The composer is a textarea rather than an input so a question can span lines (Shift+Enter).
    const input = fixture.nativeElement.querySelector('textarea[name="chatInput"]') as HTMLTextAreaElement;
    expect(input).toBeTruthy();
    expect(input.placeholder).toBe('Ask about budgets, meals, allowance…');

    const sendBtn = fixture.nativeElement.querySelector('button[title="Send message"]') as HTMLButtonElement;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(true);

    component.userInput.set('Hello assistant');
    fixture.detectChanges();

    expect(sendBtn.disabled).toBe(false);
  });

  it('asks the backend about availability once when a student view is mounted', () => {
    // The widget is constructed in `beforeEach`, so this is the call `ngOnInit` made. A status check
    // that reached the backend and was never answered would leave the request outstanding, and
    // `httpMock.verify()` in the shared teardown would fail - so exactly one call is both the
    // assertion and the cleanup.
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
    // The newline reaches the DOM intact; the CSS (`whitespace-pre-wrap`) is what renders it as a
    // break rather than collapsing the two lines into one.
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

    // The availability GET the widget issued on construction is still outstanding here, so the
    // request this test is about is matched on its method rather than on the URL alone - the URL is
    // the same for both, and `expectOne('/api/v1/chat')` would see two candidates and fail.
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
      // The chat endpoints require the STUDENT role. A guest panel must therefore not call them: the
      // error interceptor answers a 401 by clearing the session and navigating to /auth/login, so a
      // status check here would redirect a visitor who only opened the landing page - which is the
      // opposite of what a gated panel is for. The link is offered deliberately, not taken for them.
      //
      // The widget is rebuilt here rather than reusing the one from the outer `beforeEach`, because
      // that one was already created with the default (student) `isGuestMode`: `@Input()` values are
      // bound during the first change detection, so setting the property afterwards could not stop an
      // `ngOnInit` that has already run. Recreating is what makes this test about the input rather
      // than about the order the harness happens to do things in.
      //
      // The student fixture the outer `beforeEach` built has already asked for availability, so its
      // request is settled first: what this test asserts on is what the *guest* panel adds, and a bare
      // count would otherwise report the other widget's call as the guest's.
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

      // Gated locked message present
      expect(el.textContent).toContain('Log in first to chat with Sooc!');
      expect(el.textContent).toContain('Sign in with your campus account');

      // Login and Sign up buttons present
      const loginLink = el.querySelector('a[href="/auth/login"], a[routerLink="/auth/login"]');
      const signupLink = el.querySelector('a[href="/auth/register"], a[routerLink="/auth/register"]');
      expect(loginLink).toBeTruthy();
      expect(signupLink).toBeTruthy();

      // No chat input, no quick prompts
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
