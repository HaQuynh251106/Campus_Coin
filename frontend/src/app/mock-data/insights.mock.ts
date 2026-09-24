import { MonthlyInsight } from '../core/models/insight.model';

export const MOCK_INSIGHTS: MonthlyInsight[] = [
  {
    id: 'ins-2026-09',
    month: 'September 2026',
    periodCode: '2026-09',
    title: 'Mid-Semester Coffee Spike & Safe Dining Track',
    narrativeSummary: 'You are pacing well overall this month! Your total essential spending is 18% lower than August, but your Coffee & Snacks category is already at 85% of its monthly limit due to late-night study sessions at the campus library.',
    savingTip: 'Campus Hack: Switch two campus cafe lattes to the free refill station at the Student Union or brew in your dorm — this will save you roughly $18 before the month ends!',
    categoryFlag: {
      categoryName: 'Coffee & Snacks',
      percentChange: 28,
      direction: 'UP'
    },
    isBookmarked: true,
    // Real freely-licensed Unsplash portrait for the friendly AI advisor
    avatarUrl: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=256&q=80',
    createdAt: '2026-09-24T08:00:00Z'
  },
  {
    id: 'ins-2026-08',
    month: 'August 2026',
    periodCode: '2026-08',
    title: 'Textbook Season Surge & Strong Summer Savings',
    narrativeSummary: 'August was marked by high initial course preparation expenses ($110 for textbooks), yet your summer research stipend kept your net savings positive by $150.',
    savingTip: 'Tip for next term: Look for university library course reserve textbooks or senior book swaps on the campus student forum to save up to 60% on printed books.',
    categoryFlag: {
      categoryName: 'Books & Supplies',
      percentChange: 65,
      direction: 'UP'
    },
    isBookmarked: false,
    avatarUrl: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=256&q=80',
    createdAt: '2026-08-31T18:00:00Z'
  },
  {
    id: 'ins-2026-07',
    month: 'July 2026',
    periodCode: '2026-07',
    title: 'Prime Summer Savings Rate: 38% Retained',
    narrativeSummary: 'Phenomenal financial discipline! You retained 38% of your summer income. Modest recreational spending and shared roommate cooking lowered your grocery bill substantially.',
    savingTip: 'Consider moving $100 of your unspent summer balance directly into your student savings buffer before fall term starts.',
    categoryFlag: {
      categoryName: 'Food & Dining',
      percentChange: 14,
      direction: 'DOWN'
    },
    isBookmarked: true,
    avatarUrl: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=256&q=80',
    createdAt: '2026-07-31T18:00:00Z'
  }
];
