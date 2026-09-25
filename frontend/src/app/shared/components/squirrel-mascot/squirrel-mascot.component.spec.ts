import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SquirrelMascotComponent } from './squirrel-mascot.component';
import { MascotService } from '../../../core/services/mascot.service';

describe('SquirrelMascotComponent', () => {
  let component: SquirrelMascotComponent;
  let fixture: ComponentFixture<SquirrelMascotComponent>;
  let mascotService: MascotService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SquirrelMascotComponent],
      providers: [
        MascotService,
        provideRouter([])
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SquirrelMascotComponent);
    component = fixture.componentInstance;
    mascotService = TestBed.inject(MascotService);
    fixture.detectChanges();
  });

  it('should create the mascot component', () => {
    expect(component).toBeTruthy();
  });

  it('should display the squirrel SVG', () => {
    const svg = fixture.nativeElement.querySelector('svg');
    expect(svg).toBeTruthy();
  });

  it('should render speech bubble when bubbleText is present', () => {
    mascotService.bubbleText.set('Hello student!');
    fixture.detectChanges();

    const bubble = fixture.nativeElement.querySelector('.shadow-subtle-lg');
    expect(bubble).toBeTruthy();
    expect(bubble.textContent).toContain('Hello student!');
  });

  it('should toggle chat on mascot button click', () => {
    expect(mascotService.isChatOpen()).toBe(false);
    const button = fixture.nativeElement.querySelector('button');
    button.click();
    expect(mascotService.isChatOpen()).toBe(true);
  });
});
