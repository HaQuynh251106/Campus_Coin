import {
  Component,
  inject,
  ViewChild,
  ElementRef,
  AfterViewChecked,
  ChangeDetectionStrategy,
  Input
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatbotService } from '../../../core/services/chatbot.service';
import { MascotService, MascotAnchor } from '../../../core/services/mascot.service';
import { IconComponent } from '../icon/icon.component';
import { SquirrelMascotComponent } from '../squirrel-mascot/squirrel-mascot.component';

@Component({
  selector: 'app-chatbot-widget',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SquirrelMascotComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Squirrel Mascot replaces the old plain circular chatbot button -->
    <app-squirrel-mascot [initialAnchor]="initialAnchor"></app-squirrel-mascot>

    <!-- Chat Window Panel (Opens when Mascot is clicked) -->
    @if (isOpen()) {
      <div
        class="fixed bottom-24 right-4 sm:bottom-24 sm:right-6 w-[calc(100vw-2rem)] sm:w-96 h-[460px] bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-2xl shadow-subtle-lg flex flex-col overflow-hidden animate-scale-up z-50 select-none"
      >
        <!-- Chat Header -->
        <div class="px-4 py-3 bg-neutral-50 dark:bg-neutral-850 border-b border-neutral-100 dark:border-neutral-800 flex items-center justify-between">
          <div class="flex items-center gap-2.5">
            <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/25 flex items-center justify-center text-amber-700 dark:text-amber-400 shadow-xs">
              <app-icon name="squirrel-logo" [size]="18" strokeWidth="1.75"></app-icon>
            </div>
            <div>
              <h3 class="font-semibold text-xs text-neutral-900 dark:text-neutral-100 flex items-center gap-1.5">
                Campus Coin Assistant
                <span class="w-1.5 h-1.5 rounded-full bg-emerald-500" title="Online"></span>
              </h3>
              <span class="text-[10px] text-[var(--color-text-muted)] block">
                Student Financial Mascot & Coach
              </span>
            </div>
          </div>

          <button
            type="button"
            (click)="closeChat()"
            class="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 p-1 cursor-pointer rounded-md transition-colors"
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
            class="shrink-0 px-2.5 py-1 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
          >
            Food spending?
          </button>
          <button
            type="button"
            (click)="sendQuickPrompt('What is my budget status?')"
            class="shrink-0 px-2.5 py-1 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
          >
            Budget caps?
          </button>
          <button
            type="button"
            (click)="sendQuickPrompt('What is my current balance?')"
            class="shrink-0 px-2.5 py-1 rounded-full bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700 transition-colors cursor-pointer"
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
  `
})
export class ChatbotWidgetComponent implements AfterViewChecked {
  @Input() initialAnchor: MascotAnchor = 'bottom-right';

  private chatbotService = inject(ChatbotService);
  private mascotService = inject(MascotService);

  @ViewChild('scrollContainer') private scrollContainer?: ElementRef;

  userInput = '';

  isOpen = this.mascotService.isChatOpen;
  messages = this.chatbotService.messages;
  isTyping = this.chatbotService.isTyping;

  closeChat(): void {
    this.mascotService.closeChat();
  }

  toggleOpen(): void {
    this.mascotService.toggleChat();
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
