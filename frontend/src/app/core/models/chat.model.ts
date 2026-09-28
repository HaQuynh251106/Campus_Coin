

export type ChatRole = 'USER' | 'ASSISTANT';

export interface ChatTurn {
  role: ChatRole;
  text: string;
}

export interface ChatResponse {

  reply: string;

  model: string;

  toolsUsed: string[];
}

export interface ChatAvailability {
  available: boolean;

  model?: string;

  reason?: string;
}
