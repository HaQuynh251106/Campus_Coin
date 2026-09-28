import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { ChatAvailability, ChatResponse, ChatTurn } from '../models/chat.model';

export interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  time: string;
  failed?: boolean;

  retryText?: string;
}

export const CHAT_GREETING_BODY =
  "I'm your Campus Coin Assistant. Ask me about your spending, a budget, a category or last " +
  'month — I read your own records to answer.';

export const SIGNED_OUT_REPLY = 'Please sign in to ask about your finances.';

interface FailedTurn {
  readonly failed: true;
  readonly text: string;
  readonly question: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatbotService {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private readonly baseUrl = `${environment.apiUrl}/v1/chat`;

  readonly isTyping = signal(false);
  readonly messages = signal<ChatMessage[]>([]);

  readonly greetingText = signal<string>(CHAT_GREETING_BODY);

  readonly availability = signal<ChatAvailability | null>(null);

  private seq = 0;

  private lastOwnerId: string | null = null;

  constructor() {
    this.greetingText.set(this.greeting());
    this.lastOwnerId = this.currentOwnerId();
  }

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

  sendMessage(userQuery: string): void {
    const trimmed = userQuery.trim();
    if (!trimmed || this.isTyping()) {
      return;
    }

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

  reset(): void {
    this.lastOwnerId = this.currentOwnerId();
    this.isTyping.set(false);
    this.messages.set([]);
    this.greetingText.set(this.greeting());
  }

  syncToSignedInUser(): void {
    if (this.currentOwnerId() !== this.lastOwnerId) {
      this.reset();
    }
  }

  private currentOwnerId(): string | null {
    return this.auth.accessToken() ? String(this.auth.currentUser()?.id ?? '') : null;
  }

  private history(): ChatTurn[] {
    return this.messages()
      .filter(msg => !msg.failed)
      .map(msg => ({
        role: msg.sender === 'user' ? ('USER' as const) : ('ASSISTANT' as const),
        text: msg.text
      }));
  }

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

  private formatCurrentTime(): string {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
