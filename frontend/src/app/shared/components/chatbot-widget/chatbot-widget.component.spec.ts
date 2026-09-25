import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChatbotWidgetComponent } from './chatbot-widget.component';
import { ChatbotService } from '../../../core/services/chatbot.service';

describe('ChatbotWidgetComponent', () => {
  let component: ChatbotWidgetComponent;
  let fixture: ComponentFixture<ChatbotWidgetComponent>;
  let chatbotService: ChatbotService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatbotWidgetComponent],
      providers: [ChatbotService]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatbotWidgetComponent);
    component = fixture.componentInstance;
    chatbotService = TestBed.inject(ChatbotService);
    fixture.detectChanges();
  });

  it('should create the chatbot widget component', () => {
    expect(component).toBeTruthy();
  });

  it('should toggle chat window open and closed', () => {
    expect(component.isOpen()).toBe(false);
    component.toggleOpen();
    expect(component.isOpen()).toBe(true);
    component.toggleOpen();
    expect(component.isOpen()).toBe(false);
  });

  it('should send quick prompt on button click', () => {
    component.sendQuickPrompt('What is my budget status?');
    expect(chatbotService.messages().some(m => m.text === 'What is my budget status?')).toBe(true);
  });
});
