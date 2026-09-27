import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChatbotService } from './chatbot.service';
import { AuthService } from './auth.service';
import { ChatResponse } from '../models/chat.model';

/**
 * The assistant's client contract.
 *
 * Three properties are held here because each is easy to lose and expensive to lose:
 *
 * 1. **A reply is whatever the backend returned.** The service formats nothing and invents nothing.
 * 2. **A failure is a failure.** A failed request produces a flagged turn with a retry, never a
 *    sentence that could be read as the model's answer. The implementation this replaced answered
 *    from keyword matching with invented figures, and this is the test that would have caught it.
 * 3. **Ownership is not the client's to set.** No request carries a user id.
 */
describe('ChatbotService', () => {
  let service: ChatbotService;
  let httpMock: HttpTestingController;

  const apiUrl = '/api/v1/chat';

  /**
   * A stand-in for the signed-in session.
   *
   * It answers the two things the service asks of `AuthService` — the bearer token and the account —
   * and the tests can move it, so "the same student reopened the panel" and "a different student signed
   * in on the same tab" are distinguishable. The service is root-scoped, so the second case is the one
   * that matters: nothing is rebuilt between accounts except the panel.
   */
  let session: { token: string | null; user: { id: string | number; name: string; role: string } | null };

  const signIn = (id: string | number, name: string): void => {
    session = { token: `token-${id}`, user: { id, name, role: 'STUDENT' } };
  };
  const signOut = (): void => {
    session = { token: null, user: null };
  };

  beforeEach(() => {
    signIn(7, 'Alex Nguyen');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { accessToken: () => session.token, currentUser: () => session.user }
        }
      ]
    });
    service = TestBed.inject(ChatbotService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts with an empty transcript and a greeting that is not a turn in it', () => {
    // The greeting is the panel's empty state, not an assistant turn: nothing the application wrote
    // may enter the transcript, because the transcript is what gets replayed to the model.
    expect(service.messages().length).toBe(0);
    expect(service.greetingText()).toContain('Alex');
  });

  it('renders the reply the backend returned, unchanged', () => {
    const reply: ChatResponse = {
      reply: 'You spent 84.20 on Food in September.',
      model: 'gemini-3.5-flash',
      toolsUsed: ['getCategorySpending']
    };

    service.sendMessage('How much did I spend on Food?');

    const req = httpMock.expectOne(apiUrl);
    expect(req.request.method).toBe('POST');
    req.flush(reply);

    const messages = service.messages();
    expect(messages.length).toBe(2);
    expect(messages[0].sender).toBe('user');
    expect(messages[0].text).toBe('How much did I spend on Food?');
    expect(messages[1].sender).toBe('assistant');
    expect(messages[1].text).toBe('You spent 84.20 on Food in September.');
    expect(messages[1].failed).toBeFalsy();
    expect(service.isTyping()).toBe(false);
  });

  it('sends no user id — ownership is the bearer token', () => {
    service.sendMessage('What is my balance?');

    const req = httpMock.expectOne(apiUrl);
    const body = req.request.body as Record<string, unknown>;
    expect(body['userId']).toBeUndefined();
    expect(Object.keys(body).sort()).toEqual(['history', 'message']);
    req.flush({ reply: 'ok', model: 'm', toolsUsed: [] } as ChatResponse);
  });

  it('sends the conversation so far, ending with the new question', () => {
    service.sendMessage('How much did I spend this month?');
    httpMock.expectOne(apiUrl).flush({
      reply: 'You spent 271.50.',
      model: 'gemini-3.5-flash',
      toolsUsed: ['getFinancialSummary']
    } as ChatResponse);

    service.sendMessage('What about last month?');
    const req = httpMock.expectOne(apiUrl);
    const body = req.request.body as { history: { role: string; text: string }[] };

    // The first question and its reply — every turn from before this one, and no greeting.
    expect(body.history.length).toBe(2);
    expect(body.history[0].role).toBe('USER');
    expect(body.history[1].text).toBe('You spent 271.50.');
    expect(body.history[1].role).toBe('ASSISTANT');

    req.flush({ reply: 'In August you spent 300.', model: 'm', toolsUsed: [] } as ChatResponse);
  });

  it('does not send the new question twice', () => {
    service.sendMessage('What is my balance?');
    const req = httpMock.expectOne(apiUrl);
    const body = req.request.body as { message: string; history: { text: string }[] };

    expect(body.message).toBe('What is my balance?');
    expect(body.history.some(turn => turn.text === 'What is my balance?')).toBe(false);

    req.flush({ reply: 'ok', model: 'm', toolsUsed: [] } as ChatResponse);
  });

  it('reports a 503 as a failed turn with a retry, and invents no answer', () => {
    service.sendMessage('What is my balance?');

    const req = httpMock.expectOne(apiUrl);
    req.flush(
      { errorCode: 'AI_UNAVAILABLE', message: "I couldn't reach the assistant just now." },
      { status: 503, statusText: 'Service Unavailable' }
    );

    const messages = service.messages();
    const last = messages[messages.length - 1];
    expect(last.sender).toBe('assistant');
    expect(last.failed).toBe(true);
    expect(last.retryText).toBe('What is my balance?');
    // The backend's own sentence, and nothing resembling a figure.
    expect(last.text).toBe("I couldn't reach the assistant just now.");
    expect(last.text).not.toMatch(/\d/);
    expect(service.isTyping()).toBe(false);
  });

  it('drops a failed turn from the history it replays', () => {
    service.sendMessage('What is my balance?');
    httpMock.expectOne(apiUrl).flush(
      { errorCode: 'AI_UNAVAILABLE', message: 'unavailable' },
      { status: 503, statusText: 'Service Unavailable' }
    );

    service.sendMessage('What about Food?');
    const req = httpMock.expectOne(apiUrl);
    const body = req.request.body as { history: { text: string }[] };

    // The error text is not replayed to the model as if it were a previous reply.
    expect(body.history.some(turn => turn.text === 'unavailable')).toBe(false);
    req.flush({ reply: 'ok', model: 'm', toolsUsed: [] } as ChatResponse);
  });

  it('treats a blank reply as a failure rather than rendering an empty bubble', () => {
    service.sendMessage('Why?');
    httpMock.expectOne(apiUrl).flush({ reply: '   ', model: 'm', toolsUsed: [] } as ChatResponse);

    const messages = service.messages();
    const last = messages[messages.length - 1];
    expect(last.failed).toBe(true);
    expect(last.text.trim().length).toBeGreaterThan(0);
  });

  it('ignores an empty question and sends nothing', () => {
    service.sendMessage('   ');
    httpMock.expectNone(apiUrl);
    expect(service.messages().length).toBe(0);
  });

  it('records availability from the backend without spending a provider call', () => {
    service.checkAvailability();

    const req = httpMock.expectOne(apiUrl);
    expect(req.request.method).toBe('GET');
    req.flush({ available: false, reason: 'The assistant is switched off.' });

    expect(service.availability()?.available).toBe(false);
    expect(service.availability()?.reason).toBe('The assistant is switched off.');
  });

  it('leaves availability unknown when the status call fails, rather than claiming an outage', () => {
    service.checkAvailability();
    httpMock.expectOne(apiUrl).flush(
      { message: 'boom' },
      { status: 500, statusText: 'Server Error' }
    );

    // null, not false: one failed status call is not evidence that the assistant is unavailable.
    expect(service.availability()).toBeNull();
  });

  // --- Identity of the transcript -----------------------------------------------------------------
  //
  // The service is root-scoped, so it outlives the panel. These four hold the property that matters
  // after a sign-out: one account's conversation is never shown to, or replayed by, another.

  it('drops the transcript when a different account signs in on the same tab', () => {
    service.sendMessage('How much did I spend?');
    httpMock.expectOne(apiUrl).flush({
      reply: 'You spent 271.50 this month.',
      model: 'gemini-3.5-flash',
      toolsUsed: ['getFinancialSummary']
    } as ChatResponse);
    expect(service.messages().length).toBe(2);

    signOut();
    service.syncToSignedInUser();
    signIn(9, 'Bella Tran');
    service.syncToSignedInUser();

    // The previous student's question and reply are gone, and the greeting is the new student's.
    expect(service.messages().length).toBe(0);
    expect(service.greetingText()).toContain('Bella');
  });

  it('never replays one account’s history to the backend as another’s conversation', () => {
    service.sendMessage('What is my balance?');
    httpMock.expectOne(apiUrl).flush({
      reply: 'Your balance is 120.00.',
      model: 'gemini-3.5-flash',
      toolsUsed: ['getFinancialSummary']
    } as ChatResponse);

    // A different student signs in, and the panel is shown again.
    signOut();
    signIn(9, 'Bella Tran');
    service.syncToSignedInUser();

    service.sendMessage('What is my balance?');
    const body = httpMock.expectOne(apiUrl).request.body as { history: { text: string }[] };

    // The first student's figures are not in the request the second student's question travels in.
    expect(body.history.some(turn => turn.text.includes('120.00'))).toBe(false);
    expect(body.history.length).toBe(0);
  });

  it('keeps the transcript when the same student reopens the panel', () => {
    service.sendMessage('How much did I spend?');
    httpMock.expectOne(apiUrl).flush({
      reply: 'You spent 271.50.',
      model: 'gemini-3.5-flash',
      toolsUsed: []
    } as ChatResponse);

    // A route change destroys and rebuilds the panel; the account has not changed.
    service.syncToSignedInUser();

    expect(service.messages().length).toBe(2);
  });

  it('clears the transcript on sign-out, so nothing is left on screen', () => {
    service.sendMessage('What is my balance?');
    httpMock.expectOne(apiUrl).flush({
      reply: 'Your balance is 120.00.',
      model: 'gemini-3.5-flash',
      toolsUsed: []
    } as ChatResponse);

    signOut();
    service.syncToSignedInUser();

    expect(service.messages().length).toBe(0);
  });

  // --- Failure branches ---------------------------------------------------------------------------

  it('reports a 403 as a failed turn rather than an answer', () => {
    service.sendMessage('What is my balance?');
    httpMock.expectOne(apiUrl).flush(
      { errorCode: 'ACCESS_DENIED', message: 'You do not have access to this resource.' },
      { status: 403, statusText: 'Forbidden' }
    );

    const messages = service.messages();
    const last = messages[messages.length - 1];
    expect(last.failed).toBe(true);
    expect(last.retryText).toBe('What is my balance?');
    // The backend's sentence; the service invents none of its own.
    expect(last.text).toBe('You do not have access to this resource.');
  });

  it('reports a 400 as a failed turn and offers a retry', () => {
    service.sendMessage('x'.repeat(2001));
    httpMock.expectOne(apiUrl).flush(
      { errorCode: 'VALIDATION_ERROR', message: 'A message must be at most 2000 characters.' },
      { status: 400, statusText: 'Bad Request' }
    );

    const messages = service.messages();
    const last = messages[messages.length - 1];
    expect(last.failed).toBe(true);
    expect(last.text).toBe('A message must be at most 2000 characters.');
  });

  it('reports a transport failure with no body, and still flags the turn', () => {
    service.sendMessage('What is my balance?');
    httpMock.expectOne(apiUrl).error(new ProgressEvent('error'));

    const messages = service.messages();
    const last = messages[messages.length - 1];
    expect(last.failed).toBe(true);
    expect(last.retryText).toBe('What is my balance?');
    // A connection failure yields no figure and no claim about the student's money.
    expect(last.text).not.toMatch(/\d/);
    expect(service.isTyping()).toBe(false);
  });

  it('shows a loading state for the whole of a turn and clears it whatever the outcome', () => {
    service.sendMessage('What is my balance?');
    expect(service.isTyping()).toBe(true);

    const req = httpMock.expectOne(apiUrl);
    expect(service.isTyping()).toBe(true);

    req.flush(
      { errorCode: 'AI_UNAVAILABLE', message: 'unavailable' },
      { status: 503, statusText: 'Service Unavailable' }
    );
    expect(service.isTyping()).toBe(false);
  });

  it('refuses a second send while a turn is still in flight', () => {
    service.sendMessage('What is my balance?');
    const first = httpMock.expectOne(apiUrl);

    service.sendMessage('And my budget?');
    // One request, not two: the panel cannot queue a second question behind an unanswered one.
    httpMock.expectNone(apiUrl);

    first.flush({ reply: 'ok', model: 'm', toolsUsed: [] } as ChatResponse);
    expect(service.messages().length).toBe(2);
  });
});
