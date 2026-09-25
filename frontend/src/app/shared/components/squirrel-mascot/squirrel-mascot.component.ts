import {
  Component,
  Input,
  ElementRef,
  ViewChild,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
  inject,
  PLATFORM_ID,
  signal,
  computed
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { MascotService, MascotState, MascotAnchor, AnchorCoord } from '../../../core/services/mascot.service';

@Component({
  selector: 'app-squirrel-mascot',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Floating Draggable Container -->
    <div
      #mascotWrapper
      class="fixed z-40 select-none touch-none transition-all duration-300 ease-out"
      [style.left]="currentCoords().left"
      [style.right]="currentCoords().right"
      [style.top]="currentCoords().top"
      [style.bottom]="currentCoords().bottom"
      [class.transition-none]="isDragging()"
      [class.cursor-grab]="!isDragging()"
      [class.cursor-grabbing]="isDragging()"
      (mousedown)="onDragStart($event)"
      (touchstart)="onTouchStart($event)"
    >
      <!-- Contextual Speech Bubble -->
      @if (bubbleText()) {
        <div
          class="absolute bottom-full mb-2.5 left-1/2 -translate-x-1/2 w-48 sm:w-56 p-2.5 rounded-xl bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 shadow-subtle-lg text-xs leading-snug animate-fade-in pointer-events-auto"
          (click)="$event.stopPropagation()"
        >
          <div class="flex items-start justify-between gap-1.5">
            <span class="font-medium text-neutral-800 dark:text-neutral-200">
              {{ bubbleText() }}
            </span>
            <button
              type="button"
              (click)="dismissBubble($event)"
              class="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200 text-[10px] p-0.5 leading-none"
              title="Dismiss"
            >
              ✕
            </button>
          </div>

          <!-- Speech bubble bottom triangle notch -->
          <div
            class="absolute top-full left-1/2 -translate-x-1/2 -mt-px w-2 h-2 rotate-45 bg-white dark:bg-neutral-900 border-r border-b border-neutral-200 dark:border-neutral-800"
          ></div>
        </div>
      }

      <!-- Interactive Mascot Button Trigger (Replaces old circular chatbot button) -->
      <button
        type="button"
        (click)="onMascotClick($event)"
        [attr.aria-label]="isChatOpen() ? 'Close Campus Coin assistant' : 'Open Campus Coin assistant'"
        class="relative w-14 h-14 sm:w-16 sm:h-16 rounded-2xl bg-amber-500/10 hover:bg-amber-500/20 active:scale-95 border-2 border-amber-500/30 dark:border-amber-500/40 p-1.5 shadow-subtle-lg flex items-center justify-center transition-transform group backdrop-blur-xs"
        [class.ring-2]="isChatOpen()"
        [class.ring-amber-500]="isChatOpen()"
      >
        <!-- Lottie Container if available -->
        <div #lottieContainer class="w-full h-full flex items-center justify-center">
          <!-- Animated Mascot SVG Representation with 8 Dynamic States -->
          <svg
            viewBox="0 0 48 48"
            class="w-full h-full overflow-visible transition-transform duration-300"
            [class.animate-bounce-subtle]="state() === 'walk'"
            [class.animate-pulse-gentle]="state() === 'idle'"
            [class.animate-tumble]="state() === 'coin-flip'"
          >
            <defs>
              <linearGradient id="squirrelFur" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#F59E0B" />
                <stop offset="100%" stop-color="#D97706" />
              </linearGradient>
              <linearGradient id="mascotCoin" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#FDE047" />
                <stop offset="100%" stop-color="#EAB308" />
              </linearGradient>
            </defs>

            <!-- 1. Fluffy Curved Tail with state animation -->
            <path
              d="M 28 38 C 42 36 46 22 41 12 C 37 5 28 6 30 14 C 31 18 33 22 28 28 Z"
              fill="url(#squirrelFur)"
              class="origin-bottom transition-transform duration-500"
              [class.rotate-6]="state() === 'idle'"
              [class.-rotate-6]="state() === 'walk'"
            />

            <!-- 2. Pointy Ears -->
            <path d="M 12 12 L 15 22 L 9 22 Z" fill="url(#squirrelFur)" />
            <path d="M 11.5 14 L 13.5 20 L 10 20 Z" fill="#FEF3C7" />

            <path d="M 22 12 L 25 22 L 19 22 Z" fill="url(#squirrelFur)" />
            <path d="M 21.5 14 L 23.5 20 L 20 20 Z" fill="#FEF3C7" />

            <!-- 3. Head & Body -->
            <circle cx="17" cy="24" r="9" fill="url(#squirrelFur)" />
            <ellipse cx="17" cy="34" rx="8" ry="7" fill="url(#squirrelFur)" />
            <ellipse cx="17" cy="35" rx="5" ry="4.5" fill="#FEF3C7" />

            <!-- Cheeks (Puffed out in 'deposit' state) -->
            <ellipse
              cx="13"
              cy="27"
              [attr.rx]="state() === 'deposit' ? 6 : 4.5"
              [attr.ry]="state() === 'deposit' ? 4.5 : 3.5"
              fill="#FEF3C7"
              class="transition-all duration-300"
            />
            <ellipse
              cx="21"
              cy="27"
              [attr.rx]="state() === 'deposit' ? 6 : 4.5"
              [attr.ry]="state() === 'deposit' ? 4.5 : 3.5"
              fill="#FEF3C7"
              class="transition-all duration-300"
            />

            <!-- 4. Eyes & Facial Expression per State -->
            @if (state() === 'sleepy') {
              <!-- Sleeping closed curved eyes -->
              <path d="M 12 23 Q 14 25 16 23" fill="none" stroke="#78350F" stroke-width="1.2" stroke-linecap="round" />
              <path d="M 18 23 Q 20 25 22 23" fill="none" stroke="#78350F" stroke-width="1.2" stroke-linecap="round" />
              <!-- Floating Zzz -->
              <text x="24" y="16" font-size="8" font-family="monospace" font-weight="bold" fill="#F59E0B" class="animate-pulse">Z</text>
              <text x="28" y="11" font-size="6" font-family="monospace" font-weight="bold" fill="#F59E0B" class="animate-pulse">z</text>
            } @else if (state() === 'chat-open' || state() === 'guest-roaming') {
              <!-- Happy smiling curved eyes -->
              <path d="M 12 24 Q 14 21 16 24" fill="none" stroke="#18181B" stroke-width="1.5" stroke-linecap="round" />
              <path d="M 18 24 Q 20 21 22 24" fill="none" stroke="#18181B" stroke-width="1.5" stroke-linecap="round" />
            } @else {
              <!-- Alert bright eyes with reflection -->
              <circle cx="14" cy="22" r="1.8" fill="#18181B" />
              <circle cx="14.6" cy="21.4" r="0.6" fill="#FFFFFF" />

              <circle cx="20" cy="22" r="1.8" fill="#18181B" />
              <circle cx="20.6" cy="21.4" r="0.6" fill="#FFFFFF" />
            }

            <!-- Tiny Nose -->
            <polygon points="16,26 18,26 17,27.5" fill="#78350F" />

            <!-- Analyzing Glasses (Only in 'analyzing' state) -->
            @if (state() === 'analyzing') {
              <circle cx="14" cy="22" r="3.2" fill="none" stroke="#0284C7" stroke-width="1.2" />
              <circle cx="20" cy="22" r="3.2" fill="none" stroke="#0284C7" stroke-width="1.2" />
              <line x1="17.2" y1="22" x2="16.8" y2="22" stroke="#0284C7" stroke-width="1.2" />
            }

            <!-- 5. Front Paws holding Campus Coin -->
            <!-- Coin in hand -->
            <g
              class="transition-transform duration-500"
              [class.-translate-y-2]="state() === 'coin-flip'"
            >
              <circle cx="17" cy="33" r="5" fill="url(#mascotCoin)" stroke="#CA8A04" stroke-width="0.8" />
              <!-- Coin lightning bolt symbol -->
              <path d="M 17.5 30.5 L 15.5 33 L 17 33 L 16.5 35.5 L 18.5 32.8 L 17.2 32.8 Z" fill="#78350F" />
            </g>

            <ellipse cx="13" cy="33" rx="1.6" ry="2" fill="#F59E0B" />
            <ellipse cx="21" cy="33" rx="1.6" ry="2" fill="#F59E0B" />
          </svg>
        </div>

        <!-- Online Pulse Dot Indicator -->
        <span
          class="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-emerald-500 border-2 border-white dark:border-neutral-900"
          title="AI Assistant Online"
        ></span>
      </button>
    </div>
  `,
  styles: [`
    @keyframes bounceSubtle {
      0%, 100% { transform: translateY(0); }
      50% { transform: translateY(-4px); }
    }
    .animate-bounce-subtle {
      animation: bounceSubtle 0.6s infinite ease-in-out;
    }
    @keyframes pulseGentle {
      0%, 100% { transform: scale(1); }
      50% { transform: scale(1.02); }
    }
    .animate-pulse-gentle {
      animation: pulseGentle 2.5s infinite ease-in-out;
    }
    @keyframes coinTumble {
      0% { transform: rotate(0deg); }
      50% { transform: rotate(180deg) scale(1.1); }
      100% { transform: rotate(360deg); }
    }
    .animate-tumble {
      animation: coinTumble 1.2s infinite ease-in-out;
    }
  `]
})
export class SquirrelMascotComponent implements OnInit, OnDestroy {
  @Input() initialAnchor: MascotAnchor = 'bottom-right';

  @ViewChild('mascotWrapper') wrapperRef?: ElementRef<HTMLDivElement>;
  @ViewChild('lottieContainer') lottieRef?: ElementRef<HTMLDivElement>;

  private mascotService = inject(MascotService);
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  state = this.mascotService.currentState;
  bubbleText = this.mascotService.bubbleText;
  isChatOpen = this.mascotService.isChatOpen;

  // Dragging State
  isDragging = signal(false);
  private dragStartX = 0;
  private dragStartY = 0;
  private hasMoved = false;

  // Anchor points definitions (Part A.3 Snap-to-Anchor)
  private anchors: AnchorCoord[] = [
    {
      id: 'bottom-right',
      name: 'Bottom Right',
      xPercent: 0.9,
      yPercent: 0.88,
      cssStyle: { bottom: '24px', right: '24px' }
    },
    {
      id: 'bottom-left',
      name: 'Bottom Left',
      xPercent: 0.1,
      yPercent: 0.88,
      cssStyle: { bottom: '24px', left: '24px' }
    },
    {
      id: 'mid-right',
      name: 'Mid Right',
      xPercent: 0.95,
      yPercent: 0.5,
      cssStyle: { top: '48%', right: '16px' }
    },
    {
      id: 'mid-left',
      name: 'Mid Left',
      xPercent: 0.05,
      yPercent: 0.5,
      cssStyle: { top: '48%', left: '16px' }
    }
  ];

  currentAnchor = signal<MascotAnchor>('bottom-right');
  customPosition = signal<{ x: number; y: number } | null>(null);

  currentCoords = computed(() => {
    const pos = this.customPosition();
    if (pos && this.isDragging()) {
      return {
        left: `${pos.x}px`,
        top: `${pos.y}px`,
        right: 'auto',
        bottom: 'auto'
      };
    }

    const matched = this.anchors.find(a => a.id === this.currentAnchor()) || this.anchors[0];
    return {
      left: matched.cssStyle.left || 'auto',
      right: matched.cssStyle.right || 'auto',
      top: matched.cssStyle.top || 'auto',
      bottom: matched.cssStyle.bottom || 'auto'
    };
  });

  ngOnInit(): void {
    this.currentAnchor.set(this.initialAnchor);
  }

  onMascotClick(event: MouseEvent): void {
    if (this.hasMoved) {
      // Was dragging, do not open chat
      this.hasMoved = false;
      return;
    }
    event.stopPropagation();
    this.mascotService.toggleChat();
  }

  dismissBubble(event: MouseEvent): void {
    event.stopPropagation();
    this.mascotService.bubbleText.set(null);
  }

  // --- Drag and Drop with Snap to Anchor ---

  onDragStart(event: MouseEvent): void {
    if (!this.isBrowser) return;
    this.isDragging.set(true);
    this.hasMoved = false;
    this.dragStartX = event.clientX;
    this.dragStartY = event.clientY;

    const el = this.wrapperRef?.nativeElement;
    if (el) {
      const rect = el.getBoundingClientRect();
      this.customPosition.set({ x: rect.left, y: rect.top });
    }

    const onMove = (e: MouseEvent) => {
      const dx = e.clientX - this.dragStartX;
      const dy = e.clientY - this.dragStartY;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        this.hasMoved = true;
      }
      this.customPosition.update(pos => {
        if (!pos) return { x: e.clientX, y: e.clientY };
        return { x: pos.x + e.movementX, y: pos.y + e.movementY };
      });
    };

    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      this.isDragging.set(false);
      this.snapToNearestAnchor();
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }

  onTouchStart(event: TouchEvent): void {
    if (!this.isBrowser || event.touches.length !== 1) return;
    const touch = event.touches[0];
    this.isDragging.set(true);
    this.hasMoved = false;
    this.dragStartX = touch.clientX;
    this.dragStartY = touch.clientY;

    const el = this.wrapperRef?.nativeElement;
    if (el) {
      const rect = el.getBoundingClientRect();
      this.customPosition.set({ x: rect.left, y: rect.top });
    }

    let lastX = touch.clientX;
    let lastY = touch.clientY;

    const onTouchMove = (e: TouchEvent) => {
      if (e.touches.length !== 1) return;
      const t = e.touches[0];
      const dx = t.clientX - lastX;
      const dy = t.clientY - lastY;
      lastX = t.clientX;
      lastY = t.clientY;

      if (Math.abs(t.clientX - this.dragStartX) > 4) {
        this.hasMoved = true;
      }
      this.customPosition.update(pos => {
        if (!pos) return { x: t.clientX, y: t.clientY };
        return { x: pos.x + dx, y: pos.y + dy };
      });
    };

    const onTouchEnd = () => {
      window.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('touchend', onTouchEnd);
      this.isDragging.set(false);
      this.snapToNearestAnchor();
    };

    window.addEventListener('touchmove', onTouchMove, { passive: true });
    window.addEventListener('touchend', onTouchEnd);
  }

  private snapToNearestAnchor(): void {
    if (!this.isBrowser) return;
    const pos = this.customPosition();
    if (!pos) return;

    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const currentXPercent = pos.x / vw;
    const currentYPercent = pos.y / vh;

    // Find closest anchor based on Euclidean distance
    let minDistance = Infinity;
    let closestAnchor = this.anchors[0].id;

    for (const a of this.anchors) {
      const dist = Math.hypot(a.xPercent - currentXPercent, a.yPercent - currentYPercent);
      if (dist < minDistance) {
        minDistance = dist;
        closestAnchor = a.id;
      }
    }

    this.currentAnchor.set(closestAnchor);
    this.customPosition.set(null); // Return to CSS anchor positioning with smooth transition
  }

  ngOnDestroy(): void {}
}
