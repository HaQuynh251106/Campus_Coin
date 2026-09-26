import {
  Component,
  inject,
  ViewChild,
  ElementRef,
  AfterViewChecked,
  ChangeDetectionStrategy,
  Input,
  signal
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

    <!-- Chat Window Panel (Opens when Mascot is clicked; hides mascot launcher per A.2) -->
    @if (isOpen()) {
      <div
        class="fixed bottom-20 right-4 sm:bottom-6 sm:right-6 w-[calc(100vw-2rem)] sm:w-96 h-[460px] bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-2xl shadow-subtle-lg flex flex-col overflow-hidden animate-scale-up z-50 select-none"
      >
        <!-- Chat Header with explicit close (✕) control (A.2) -->
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
            class="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200 p-1 cursor-pointer rounded-md transition-colors text-xs"
            title="Close chat"
            aria-label="Close chat"
          >
            ✕
          </button>
        </div>

        <!-- Messages Scrollable Body with Refined Thin Scrollbar (A.1) -->
        <div
          #scrollContainer
          class="flex-1 p-3.5 overflow-y-auto space-y-3 bg-neutral-50/40 dark:bg-neutral-900/60 chat-scrollbar"
        >
          @for (msg of messages(); track msg.id) {
            @if (msg.sender === 'assistant') {
              <!-- Assistant Message with Bot Mascot Avatar (A.4) -->
              <div class="flex items-start gap-2 max-w-[88%]">
                <div class="w-6 h-6 rounded-full bg-amber-500/15 border border-amber-500/30 flex items-center justify-center text-amber-700 dark:text-amber-400 shrink-0 mt-0.5 shadow-xs">
                  <app-icon name="squirrel-logo" [size]="13" strokeWidth="1.8"></app-icon>
                </div>
                <div class="flex flex-col items-start">
                  <div
                    class="rounded-2xl p-3 text-xs leading-relaxed bg-white dark:bg-neutral-800 text-neutral-800 dark:text-neutral-200 border border-neutral-200/80 dark:border-neutral-700/80 rounded-tl-xs shadow-xs"
                  >
                    {{ msg.text }}
                  </div>
                  <span class="text-[9px] text-[var(--color-text-muted)] mt-1 px-1 font-mono">
                    {{ msg.time }}
                  </span>
                </div>
              </div>
            } @else {
              <!-- User Message -->
              <div class="flex flex-col items-end self-end max-w-[85%]">
                <div
                  class="rounded-2xl p-3 text-xs leading-relaxed bg-amber-500/15 text-neutral-950 dark:text-amber-100 border border-amber-500/30 rounded-tr-xs shadow-xs"
                >
                  {{ msg.text }}
                </div>
                <span class="text-[9px] text-[var(--color-text-muted)] mt-1 px-1 font-mono">
                  {{ msg.time }}
                </span>
              </div>
            }
          }

          <!-- Typing Indicator with Squirrel Avatar (A.4) -->
          @if (isTyping()) {
            <div class="flex items-start gap-2 max-w-[88%]">
              <div class="w-6 h-6 rounded-full bg-amber-500/15 border border-amber-500/30 flex items-center justify-center text-amber-700 dark:text-amber-400 shrink-0 mt-0.5 shadow-xs">
                <app-icon name="squirrel-logo" [size]="13" strokeWidth="1.8"></app-icon>
              </div>
              <div class="flex items-center gap-1.5 p-3 rounded-2xl rounded-tl-xs bg-white dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 shadow-xs">
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce [animation-delay:0.15s]"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-amber-500 animate-bounce [animation-delay:0.3s]"></span>
              </div>
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

        <!-- Chat Input Footer with Accessible Contrast (A.3) -->
        <form (ngSubmit)="onSend()" class="p-2.5 border-t border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 flex items-center gap-2">
          <input
            type="text"
            [value]="userInput()"
            (input)="userInput.set($any($event.target).value)"
            name="chatInput"
            placeholder="Ask about budgets, meals, allowance…"
            [disabled]="isTyping()"
            class="flex-1 px-3 py-1.5 text-xs bg-neutral-50 dark:bg-neutral-800 border border-neutral-200 dark:border-neutral-700 rounded-lg text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-600 dark:placeholder:text-neutral-400 focus:outline-none focus:ring-1 focus:ring-amber-500"
          />
          <button
            type="submit"
            [disabled]="!userInput().trim() || isTyping()"
            class="p-2 rounded-lg shadow-xs transition-all duration-150 flex items-center justify-center border"
            [class.bg-amber-500]="userInput().trim() && !isTyping()"
            [class.hover:bg-amber-600]="userInput().trim() && !isTyping()"
            [class.active:scale-95]="userInput().trim() && !isTyping()"
            [class.text-neutral-950]="userInput().trim() && !isTyping()"
            [class.border-amber-600/30]="userInput().trim() && !isTyping()"
            [class.cursor-pointer]="userInput().trim() && !isTyping()"
            [class.bg-neutral-100]="!userInput().trim() || isTyping()"
            [class.dark:bg-neutral-800]="!userInput().trim() || isTyping()"
            [class.text-neutral-400]="!userInput().trim() || isTyping()"
            [class.dark:text-neutral-500]="!userInput().trim() || isTyping()"
            [class.border-neutral-200]="!userInput().trim() || isTyping()"
            [class.dark:border-neutral-700]="!userInput().trim() || isTyping()"
            [class.cursor-not-allowed]="!userInput().trim() || isTyping()"
            title="Send message"
          >
            <app-icon name="arrow-right" [size]="14" strokeWidth="2.5"></app-icon>
          </button>
        </form>
      </div>
    }
  `,
  styles: [`
    /* A.1 Refined Custom Scrollbar: 5px, translucent, rounded, no arrows */
    .chat-scrollbar {
      scrollbar-width: thin;
      scrollbar-color: rgba(156, 163, 175, 0.35) transparent;
    }
    .chat-scrollbar::-webkit-scrollbar {
      width: 5px;
    }
    .chat-scrollbar::-webkit-scrollbar-track {
      background: transparent;
    }
    .chat-scrollbar::-webkit-scrollbar-thumb {
      background: rgba(156, 163, 175, 0.35);
      border-radius: 9999px;
    }
    .chat-scrollbar::-webkit-scrollbar-thumb:hover {
      background: rgba(156, 163, 175, 0.6);
    }
    .chat-scrollbar::-webkit-scrollbar-button {
      display: none;
      width: 0;
      height: 0;
    }
    :host-context(.dark) .chat-scrollbar,
    .dark .chat-scrollbar {
      scrollbar-color: rgba(255, 255, 255, 0.2) transparent;
    }
    :host-context(.dark) .chat-scrollbar::-webkit-scrollbar-thumb,
    .dark .chat-scrollbar::-webkit-scrollbar-thumb {
      background: rgba(255, 255, 255, 0.2);
    }
    :host-context(.dark) .chat-scrollbar::-webkit-scrollbar-thumb:hover,
    .dark .chat-scrollbar::-webkit-scrollbar-thumb:hover {
      background: rgba(255, 255, 255, 0.4);
    }
  `]
})
export class ChatbotWidgetComponent implements AfterViewChecked {
  @Input() initialAnchor: MascotAnchor = 'bottom-right';

  private chatbotService = inject(ChatbotService);
  private mascotService = inject(MascotService);

  @ViewChild('scrollContainer') private scrollContainer?: ElementRef;

  userInput = signal('');

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
    const text = this.userInput().trim();
    if (!text || this.isTyping()) return;
    this.userInput.set('');
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
