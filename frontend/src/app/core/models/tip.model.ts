export type TipState = 'NEW' | 'PINNED' | 'DISMISSED';

export interface SavingTip {
  id: number;
  categoryId?: number;
  title: string;
  body: string;
  potentialSaving: number;
  state: TipState;
  pinnedAt?: string;
  dismissedAt?: string;
}

export interface TipMonthsResponse {
  months: string[];
}

export interface Bookmark {
  id: number;
  itemType: 'TIP' | 'INSIGHT';
  tipId: number;
  tipTitle: string;
  tipBody: string;
  tipPotentialSaving: number;
  tipState: TipState;
  tipMonth: string;
  note?: string;
  createdAt: string;
}
