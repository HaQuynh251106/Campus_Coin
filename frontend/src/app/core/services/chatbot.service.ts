import { Injectable, signal } from '@angular/core';

export interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  time: string;
}

/**
 * ChatbotService
 * NOTE: This is a lightweight mock service simulating an AI assistant
 * with keyword-matched financial responses and a simulated typing delay.
 * It serves as a placeholder for a future real AI/chatbot backend
 * (such as an LLM endpoint, Tidio, or tawk.to as referenced in the SRS).
 */
@Injectable({
  providedIn: 'root'
})
export class ChatbotService {
  readonly isTyping = signal(false);

  readonly messages = signal<ChatMessage[]>([
    {
      id: 'msg-welcome',
      sender: 'assistant',
      text: "Hi Alex! I'm your Campus Coin Assistant. Ask me about your spending, current budget limits, or tips to stretch your student allowance.",
      time: 'Just now'
    }
  ]);

  sendMessage(userQuery: string): void {
    const trimmed = userQuery.trim();
    if (!trimmed || this.isTyping()) return;

    const userMsg: ChatMessage = {
      id: `msg-${Date.now()}`,
      sender: 'user',
      text: trimmed,
      time: this.formatCurrentTime()
    };

    this.messages.update(prev => [...prev, userMsg]);
    this.isTyping.set(true);

    // Simulate short network & LLM inference delay
    setTimeout(() => {
      const responseText = this.generateResponse(trimmed);
      const assistantMsg: ChatMessage = {
        id: `msg-${Date.now() + 1}`,
        sender: 'assistant',
        text: responseText,
        time: this.formatCurrentTime()
      };
      this.messages.update(prev => [...prev, assistantMsg]);
      this.isTyping.set(false);
    }, 600);
  }

  private generateResponse(query: string): string {
    const lower = query.toLowerCase();

    if (lower.includes('food') || lower.includes('dining') || lower.includes('eat') || lower.includes('lunch') || lower.includes('dinner')) {
      return "You've spent $46.50 on Food & Dining so far this month, which is about 21% of your $220 limit. You're well within your safe zone!";
    }

    if (lower.includes('coffee') || lower.includes('snack') || lower.includes('latte') || lower.includes('boba')) {
      return "You've spent $29.80 on Coffee & Snacks out of your $35 monthly cap (85%). You're close to the warning threshold, so consider dorm brewing for the rest of the week.";
    }

    if (lower.includes('budget') || lower.includes('cap') || lower.includes('limit') || lower.includes('envelope')) {
      return "Your total monthly envelope is $685 across all categories. You've spent $423.50 (62% overall). Housing ($280) and Coffee ($29.80) are nearing their limits.";
    }

    if (lower.includes('balance') || lower.includes('save') || lower.includes('savings') || lower.includes('net') || lower.includes('income')) {
      return "Your net balance for September is +$423.50 with a 77% savings rate, bolstered by your CS lab assistant paycheck and monthly family allowance.";
    }

    if (lower.includes('transport') || lower.includes('transit') || lower.includes('bus') || lower.includes('uber')) {
      return "You've spent $5.50 on transit against a $30 limit. You have plenty of headroom for weekend trips or campus shuttles.";
    }

    if (lower.includes('book') || lower.includes('supplies') || lower.includes('class') || lower.includes('course')) {
      return "You spent $22.00 on Books & Supplies this month (calculus lab notebook). You have $38 remaining in that envelope.";
    }

    if (lower.includes('help') || lower.includes('how') || lower.includes('what can you do')) {
      return "I can help you review category spending, check budget caps, or give quick tips. Try asking: 'How is my food spending?', 'What is my budget status?', or 'How much have I saved?'";
    }

    return "I analyzed your September receipts: you're tracking safely across most categories, with 77% net savings. Ask about specific categories like food, coffee, or your overall budget status!";
  }

  private formatCurrentTime(): string {
    const now = new Date();
    return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
