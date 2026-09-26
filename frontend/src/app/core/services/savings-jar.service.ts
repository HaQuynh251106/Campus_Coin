import { Injectable, signal, inject } from '@angular/core';
import { MascotService } from './mascot.service';

export interface FloatingCoinFeedback {
  id: number;
  text: string;
  xOffset: number; // in pixels relative to center of jar
}

@Injectable({
  providedIn: 'root'
})
export class SavingsJarService {
  private mascotService = inject(MascotService);

  readonly savedCount = signal<number>(0);
  readonly floatingCoins = signal<FloatingCoinFeedback[]>([]);

  private readonly praiseMessages = [
    'Nice! Keep stacking those coins!',
    'Another coin in the jar! Every bit counts!',
    'Cha-ching! Building those savings habits!',
    'Great catch! Look at that savings jar grow!',
    'Smart spending! Tucking that one away!'
  ];
  private praiseIndex = 0;

  recordCoinCaught(worldX: number, pixelsPerUnit = 35): void {
    // Increment running counter
    this.savedCount.update(c => c + 1);

    // Calculate pixel offset from center
    const xOffset = Math.round(worldX * pixelsPerUnit);
    const feedbackId = Date.now() + Math.random();

    this.floatingCoins.update(list => [
      ...list,
      { id: feedbackId, text: '+1 Coin', xOffset }
    ]);

    // Automatically remove after ~1s float animation
    setTimeout(() => {
      this.floatingCoins.update(list => list.filter(f => f.id !== feedbackId));
    }, 1100);

    // Occasionally (every 5th coin caught) trigger mascot speech-bubble praise
    const count = this.savedCount();
    if (count > 0 && count % 5 === 0) {
      const msg = this.praiseMessages[this.praiseIndex % this.praiseMessages.length];
      this.praiseIndex++;
      this.mascotService.triggerState('deposit', msg, 4000);
    }
  }

  reset(): void {
    this.savedCount.set(0);
    this.floatingCoins.set([]);
  }
}
