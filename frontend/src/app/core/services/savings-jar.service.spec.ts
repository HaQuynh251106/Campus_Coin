import { describe, it, expect, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SavingsJarService } from './savings-jar.service';
import { MascotService } from './mascot.service';

describe('SavingsJarService', () => {
  let service: SavingsJarService;
  let mascotService: MascotService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SavingsJarService,
        MascotService,
        provideRouter([])
      ]
    });

    service = TestBed.inject(SavingsJarService);
    mascotService = TestBed.inject(MascotService);
  });

  it('should initialize with 0 coins saved', () => {
    expect(service.savedCount()).toBe(0);
    expect(service.floatingCoins().length).toBe(0);
  });

  it('should increment saved count and add floating coin feedback on catch', () => {
    service.recordCoinCaught(0.5, 35);
    expect(service.savedCount()).toBe(1);
    expect(service.floatingCoins().length).toBe(1);
    expect(service.floatingCoins()[0].text).toBe('+1 Coin');
  });

  it('should trigger mascot praise every 5th coin caught', () => {
    const triggerSpy = vi.spyOn(mascotService, 'triggerState');

    // Coins 1 to 4: no praise
    for (let i = 1; i <= 4; i++) {
      service.recordCoinCaught(0);
    }
    expect(triggerSpy).not.toHaveBeenCalled();

    // Coin 5: triggers mascot praise
    service.recordCoinCaught(0);
    expect(service.savedCount()).toBe(5);
    expect(triggerSpy).toHaveBeenCalledWith('deposit', expect.any(String), 4000);
  });

  it('should reset saved count and floating coins', () => {
    service.recordCoinCaught(0);
    service.recordCoinCaught(0);
    expect(service.savedCount()).toBe(2);

    service.reset();
    expect(service.savedCount()).toBe(0);
    expect(service.floatingCoins().length).toBe(0);
  });
});
