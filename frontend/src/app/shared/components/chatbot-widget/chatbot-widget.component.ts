import { Component, inject, signal, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatbotService, ChatMessage } from '../../../core/services/chatbot.service';
import { IconComponent } from '../icon/icon.component';

@Component({
  selector: 'app-chatbot-widget',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  template: `
    <!-- Floating Circular Trigger Button -->
    <div class="fixed bottom-20 right-4 sm:bottom-6 sm:right-6 z-40 select-none">
      <button
        type="button"
        (click)="toggleOpen()"
        [attr.aria-label]="isOpen() ? 'Close financial assistant' : 'Open financial assistant'"
        class="w-12 h-12 rounded-full bg-amber-500 hover:bg-amber-600 active:scale-95 text-neutral-950 shadow-subtle-lg flex items-center justify-center transition-all cursor-pointer border border-amber-400 group"
      >
        @if (isOpen()) {
          <app-icon name="x" [size]="20" strokeWidth="2"></app-icon>
        } @else {
          <app-icon name="message-square" [size]="20" strokeWidth="1.5" className="group-hover:scale-110 transition-transform"></app-icon>
        }
      </button>

      <!-- Chat Window Panel -->
      @if (isOpen()) {
        <div
          class="fixed bottom-36 right-4 sm:bottom-20 sm:right-6 w-[calc(100vw-2rem)] sm:w-96 h-[460px] bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-2xl shadow-subtle-lg flex flex-col overflow-hidden animate-scale-up z-50"
        >
          <!-- Chat Header -->
          <div class="px-4 py-3 bg-neutral-50 dark:bg-neutral-850 border-b border-neutral-100 dark:border-neutral-800 flex items-center justify-between">
            <div class="flex items-center gap-2.5">
              <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/25 flex items-center justify-center text-sm font-bold text-amber-700 dark:text-amber-400">
                ⚡
              </div>
              <div>
                <h3 class="font-semibold text-xs text-neutral-900 dark:text-neutral-100 flex items-center gap-1.5">
                  Campus Coin Assistant
                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
                </h3>
                <span class="text-[10px] text-[var(--color-text-muted)] block">
                  Student Finance Coach
                </span>
              </div>
            </div>

            <button
              type="button"
              (click)="isOpen.set(false)"
              class="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 p-1 cursor-pointer rounded-md"
              title="Close chat"
            >
              ✕
            </button>
          </div>

          <!-- Messages Scrollable Body -->
          <div
            #scrollContainer
            class="flex-1 p-3.5 overflow-y-auto space-y-3 bg-neutral-50/40 dark:bg-neutral-900/60"
          >
            @for (msg of messages(); track msg.id) {
              <div
                class="flex flex-col"
                [class.items-end]="msg.sender === 'user'"
                [class.items-start]="msg.sender === 'assistant'"
              >
                <div
                  class="rounded-2xl p-3 text-xs leading-relaxed max-w-[85%]"
                  [class.bg-amber-500/15]="msg.sender === 'user'"
                  [class.text-neutral-950]="msg.sender === 'user'"
                  [class.dark:text-amber-100]="msg.sender === 'user'"
                  [class.border]="true"
                  [class.border-amber-500/30]="msg.sender === 'user'"
                  [class.rounded-tr-xs]="msg.sender === 'user'"
                  [class.bg-white]="msg.sender === 'assistant'"
                  [class.dark:bg-neutral-800]="msg.sender === 'assistant'"
                  [class.text-neutral-800]="msg.sender === 'assistant'"
                  [class.dark:text-neutral-200]="msg.sender === 'assistant'"
                  [class.border-neutral-200/80]="msg.sender === 'assistant'"
                  [class.dark:border-neutral-700/80]="msg.sender === 'assistant'"
                  [class.rounded-tl-xs]="msg.sender === 'assistant'"
                  [class.shadow-xs]="true"
                >
                  {{ msg.text }}
                </div>
                <span class="text-[9px] text-[var(--color-text-muted)] mt-1 px-1 font-mono">
                  {{ msg.time }}
                </span>
              </div>
            }

            <!-- Typing Indicator -->
            @if (isTyping()) {
              <div class="flex items-center gap-1.5 p-3 rounded-2xl rounded-tl-xs bg-white dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 max-w-[80px] shadow-xs">
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce [animation-delay:0.15s]"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce [animation-delay:0.3s]"></span>
              </div>
            }
          </div>

          <!-- Quick Suggestion Chips -->
          <div class="px-3 py-1.5 border-t border-neutral-100 dark:border-neutral-800 bg-white dark:bg-neutral-900 flex items-center gap-1.5 overflow-x-auto text-[11px] no-scrollbar">
            <button
              type="button"
              (click)="sendQuickPrompt('How is my food spending?')"
              class="shrink-0 px-2 py-0.5 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
            >
              Food spending?
            </button>
            <button
              type="button"
              (click)="sendQuickPrompt('What is my budget status?')"
              class="shrink-0 px-2 py-0.5 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
            >
              Budget caps?
            </button>
            <button
              type="button"
              (click)="sendQuickPrompt('What is my current balance?')"
              class="shrink-0 px-2 py-0.5 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
            >
              Balance & savings?
            </button>
          </div>

          <!-- Chat Input Footer -->
          <form (ngSubmit)="onSend()" class="p-2.5 border-t border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 flex items-center gap-2">
            <input
              type="text"
              [(ngModel)]="userInput"
              name="chatInput"
              placeholder="Ask about budgets, meals, allowance…"
              [disabled]="isTyping()"
              class="flex-1 px-3 py-1.5 text-xs bg-neutral-50 dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 rounded-lg text-neutral-900 dark:text-neutral-100 focus:outline-none focus:ring-1 focus:ring-amber-500"
            />
            <button
              type="submit"
              [disabled]="!userInput.trim() || isTyping()"
              class="p-2 bg-amber-500 hover:bg-amber-600 disabled:opacity-40 text-neutral-950 rounded-lg shadow-xs transition-colors cursor-pointer"
              title="Send message"
            >
              <app-icon name="arrow-right" [size]="14" strokeWidth="2"></app-icon>
            </button>
          </form>
        </div>
      }
    </div>
  `
})
export class ChatbotWidgetComponent implements AfterViewChecked {
  private chatbotService = inject(ChatbotService);

  @ViewChild('scrollContainer') private scrollContainer?: ElementRef;

  isOpen = signal(false);
  userInput = '';

  messages = this.chatbotService.messages;
  isTyping = this.chatbotService.isTyping;

  toggleOpen(): void {
    this.isOpen.set(!this.isOpen());
  }

  sendQuickPrompt(prompt: string): void {
    this.chatbotService.sendMessage(prompt);
  }

  onSend(): void {
    if (!this.userInput.trim() || this.isTyping()) return;
    const text = this.userInput;
    this.userInput = '';
    this.chatbotService.sendMessage(text);
  }

  ngAfterViewChecked(): void {
    this.scrollToBottom();
  }

  private scrollToBottom(): void {
    if (this.scrollContainer) {
      try {
        this.scrollContainer.nativeElement.scrollTop = this.scrollContainer.nativeElement.scrollHeight;
      } catch {}
    }
  }
}
