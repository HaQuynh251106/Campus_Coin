import { CategoryType } from './category.model';

export type SuggestionSource = 'AI' | 'RULE' | 'NONE';

export type RuleSource = 'ACCEPTED' | 'OVERRIDE' | 'IMPORT';

export interface LearnedCategoryRule {

  keyword: string;
  categoryId: number;
  categoryName: string;

  source?: RuleSource;
}

export interface CategorySuggestion {

  transactionId: number;
  source: SuggestionSource;

  categoryId?: number;
  categoryName?: string;
  type?: CategoryType;

  confidence?: number;

  reason?: string;
  learned?: LearnedCategoryRule;
}

export interface SuggestCategoryRequest {
  transactionId: number;
}
