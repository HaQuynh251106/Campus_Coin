import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { ChatAvailability, ChatResponse, ChatTurn } from '../models/chat.model';

/**
 * One bubble in the chat panel.
 *
 * `failed` marks an assistant bubble that is not a reply at all but the record of a turn that could
 * not be answered. It is a separate flag rather than a sentinel string so the panel styles it as a
 * failure and offers a retry, and so nothing downstream can mistake it for prose the model wrote —
 * which is exactly the confusion section 15 of the rebuild brief forbids.
 */
export interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  time: string;
  failed?: boolean;
  /** The question that produced a failed turn, so the panel can offer to send it again. */
  retryText?: string;
}

/** The panel's empty state: the sentence shown before anything has been asked. */
export const CHAT_GREETING_BODY =
  "I'm your Campus Coin Assistant. Ask me about your spending, a budget, a category or last " +
  'month — I read your own records to answer.';

/** Shown when no student is signed in. Nothing is sent to the backend in this state. */
export const SIGNED_OUT_REPLY = 'Please sign in to ask about your finances.';

/**
 * A turn that could not be answered, kept apart from a reply by its type.
 *
 * This is a **client-side message about a failure**, never a stand-in for an answer: it carries the
 * question to offer back and no figure or claim about the student's money. The separate type is what
 * keeps it from being rendered as a reply — an earlier version funnelled the error text through the
 * `ChatResponse` shape, where non-blank text was indistinguishable from something the model wrote,
 * and the panel showed the failure as an ordinary assistant bubble. The distinction matters because
 * the file this replaces did exactly that: it answered from keyword matching and rendered invented
 * figures as the student's own.
 */
interface FailedTurn {
  readonly failed: true;
  readonly text: string;
  readonly question: string;
}

/**
 * The in-app assistant: a thin client for `POST /api/v1/chat`.
 *
 * **The reply is written by a language model on the server.** This service sends the question and the
 * conversation so far, and renders what comes back. It calculates nothing, formats no figure and
 * holds no fallback answer, because it has no data of its own to answer from — every number the
 * assistant quotes was read server-side from the student's own rows under their bearer token.
 *
 * **When the request fails, the panel says so.** The backend answers `503 AI_UNAVAILABLE` when the
 * provider is unreachable, rate-limited or its daily quota is spent, and it deliberately returns no
 * reply body rather than composing one. This service does not fill that gap: it records a failed turn
 * so the panel can offer a retry. Composing an answer here would present application-written text as
 * the assistant's, which is the one thing the feature's brief rules out.
 *
 * **There is no session.** The conversation is this signal, and it is sent with each request. Nothing
 * is stored server-side, so nothing can be attributed to the wrong student, and reloading the page
 * starts over.
 */
@Injectable({
  providedIn: 'root'
})
export class ChatbotService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private readonly baseUrl = `${environment.apiUrl}/v1/chat`;

  readonly isTyping = signal(false);
  readonly messages = signal<ChatMessage[]>([]);

  /**
   * The panel's empty state, and **not** part of the transcript.
   *
   * It is deliberately not pushed into `messages`, because it is text this application wrote and the
   * model did not. Putting it in the array would make it a turn the client replays to the provider as
   * a previous reply — the same mistake of presenting application-written text as the assistant's
   * that section 15 of the rebuild brief rules out, only in the other direction.
   */
  readonly greetingText = signal<string>(CHAT_GREETING_BODY);

  /**
   * Whether the backend can reach a provider, or `null` while that is still unknown.
   *
   * `null` is not `false`: the panel should not claim the assistant is unavailable before it has
   * asked. The `GET` costs no provider quota, so it is safe to call on open.
   */
  readonly availability = signal<ChatAvailability | null>(null);

  private seq = 0;

  /**
   * The account whose transcript the panel is currently showing, or `null` for nobody.
   *
   * Set from the signed-in account at construction and after every purge, so
   * {@link syncToSignedInUser} can tell "same student, panel reopened" from "a different student".
   */
  private lastOwnerId: string | null = null;

  constructor() {
    this.greetingText.set(this.greeting());
    this.lastOwnerId = this.currentOwnerId();
  }

  /**
   * Asks the backend whether the assistant is available and records the answer.
   *
   * A failure here is left as `null` rather than forced to `false`: the panel should keep accepting
   * questions and let a real send report the real problem, instead of showing an outage notice
   * because one status call failed.
   */
  checkAvailability(): void {
    this.http
      .get<ChatAvailability>(this.baseUrl)
      .pipe(catchError(() => of(null)))
      .subscribe(res => {
        if (res) {
          this.availability.set(res);
        }
      });
  }

  /**
   * Sends one question and renders the reply.
   *
   * The conversation so far is read from the current messages **before** the new question is pushed,
   * so the transcript the model receives is the turns it has not yet seen plus the new one — the
   * shape `ChatTurn[]` describes, oldest first, with the new question last in `message`.
   */
  sendMessage(userQuery: string): void {
    const trimmed = userQuery.trim();
    if (!trimmed || this.isTyping()) {
      return;
    }

    // Before the transcript is read, not after: a turn must never be sent alongside another account's
    // history, and the read below is what puts it in the request body.
    this.syncToSignedInUser();

    const history = this.history();
    this.messages.update(list => [...list, this.userMessage(trimmed)]);
    this.isTyping.set(true);

    this.http
      .post<ChatResponse>(this.baseUrl, { message: trimmed, history })
      .pipe(catchError(err => of(this.failure(err, trimmed))))
      .subscribe(outcome => {
        this.isTyping.set(false);
        this.messages.update(list => [...list, this.assistantMessage(outcome, trimmed)]);
      });
  }

  /** Clears the transcript. Used when a student signs out, so one account's chat cannot be shown to another. */
  reset(): void {
    this.lastOwnerId = this.currentOwnerId();
    this.isTyping.set(false);
    this.messages.set([]);
    this.greetingText.set(this.greeting());
  }

  /**
   * Clears the transcript when the signed-in account is not the one it belongs to.
   *
   * **This service is root-scoped, so it outlives the panel that shows it.** A chat panel is destroyed
   * and rebuilt on a route change, but this service is not, so its `messages` signal survives a sign-out.
   * Without this check the next student to sign in on the same tab would open the panel onto the previous
   * student's replies — and, because {@link sendMessage} sends the transcript back, the earlier student's
   * *questions* would be replayed to the backend as the new student's conversation. The server is not
   * fooled, because every read follows the bearer token; the harm is that the client hands the model
   * another account's context and shows their money to the wrong person. Ownership is the token's job on
   * the server and the transcript's job here.
   *
   * It is called when the panel is constructed, so a stale transcript is never rendered even once, and
   * again before each send, so no path can replay one.
   */
  syncToSignedInUser(): void {
    if (this.currentOwnerId() !== this.lastOwnerId) {
      this.reset();
    }
  }

  /**
   * Who the transcript on screen belongs to: the signed-in account's id, or `null` when signed out.
   *
   * The id rather than the token, so a token refresh for the same student does not discard their
   * conversation; and a token's *presence* rather than only the user, so signing out is itself a change
   * of owner. `currentUser()` can lag behind `accessToken()` while the profile loads, which reads as the
   * empty-string owner and simply clears a transcript that is empty at startup anyway.
   */
  private currentOwnerId(): string | null {
    return this.auth.accessToken() ? String(this.auth.currentUser()?.id ?? '') : null;
  }

  /**
   * The conversation as the backend expects it: oldest first, no failed turns.
   *
   * A failed turn is dropped rather than sent. It is the panel's record of a request that did not
   * complete, not something either party said, and replaying it would put a line of error text into
   * the model's context as if it were a previous reply.
   */
  private history(): ChatTurn[] {
    return this.messages()
      .filter(msg => !msg.failed)
      .map(msg => ({
        role: msg.sender === 'user' ? ('USER' as const) : ('ASSISTANT' as const),
        text: msg.text
      }));
  }

  /**
   * Turns a transport failure into what the panel renders.
   *
   * `503` is the expected case and carries the backend's own student-readable sentence, so it is
   * shown as-is. Anything else — a timeout the interceptor rewrote, a `400` on an over-long message,
   * a network error — is reported plainly. In every branch a **failed turn** is produced, which is a
   * distinct type from a reply, so there is no path by which an error sentence reaches the panel as
   * something the model said.
   */
  private failure(err: unknown, question: string): FailedTurn {
    const status = err instanceof HttpErrorResponse ? err.status : 0;
    const body = err instanceof HttpErrorResponse ? err.error : null;
    const serverMessage = body && typeof body === 'object' ? (body as { message?: string }).message : null;

    let text: string;
    if (serverMessage) {
      text = serverMessage;
    } else if (status === 400) {
      text = 'That message could not be sent — try shortening it.';
    } else {
      text = "I couldn't reach the assistant just now. Please try again in a moment.";
    }

    return { failed: true, text, question };
  }

  private userMessage(text: string): ChatMessage {
    return { id: this.nextId('user'), sender: 'user', text, time: this.formatCurrentTime() };
  }

  /**
   * The assistant's bubble for one turn — either the model's reply or a flagged failure.
   *
   * The two arrive as different types, so this is the single place that decides which one the panel
   * gets. A reply with no text cannot happen (the backend treats a blank provider response as a fault
   * and answers `503`), but if it ever did, it is reported as a failure rather than rendered as an
   * empty bubble, so the student is never shown a blank line where an answer should be.
   */
  private assistantMessage(outcome: ChatResponse | FailedTurn, question: string): ChatMessage {
    if ('failed' in outcome) {
      return {
        id: this.nextId('assistant'),
        sender: 'assistant',
        text: outcome.text,
        time: this.formatCurrentTime(),
        failed: true,
        retryText: question
      };
    }

    const text = (outcome.reply ?? '').trim();
    if (!text) {
      return {
        id: this.nextId('assistant'),
        sender: 'assistant',
        text: "I didn't get an answer back. Please try again.",
        time: this.formatCurrentTime(),
        failed: true,
        retryText: question
      };
    }
    return {
      id: this.nextId('assistant'),
      sender: 'assistant',
      text,
      time: this.formatCurrentTime()
    };
  }

  private greeting(): string {
    const name = this.auth.currentUser()?.name?.trim().split(/\s+/)[0];
    return name ? `Hi ${name}! ${CHAT_GREETING_BODY}` : `Hi! ${CHAT_GREETING_BODY}`;
  }

  private nextId(prefix: string): string {
    this.seq += 1;
    return `msg-${prefix}-${this.seq}`;
  }

  /** The panel shows a local clock time; the backend sends none and this is presentation only. */
  private formatCurrentTime(): string {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
