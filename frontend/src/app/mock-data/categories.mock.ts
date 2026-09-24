import { Category } from '../core/models/category.model';

export const MOCK_CATEGORIES: Category[] = [
  // Expense Categories (Default System)
  {
    id: 'cat-exp-1',
    name: 'Food & Dining',
    type: 'EXPENSE',
    icon: 'utensils',
    color: '#FFE600',
    isDefault: true,
    description: 'Campus cafeteria, dining halls, groceries & restaurants'
  },
  {
    id: 'cat-exp-2',
    name: 'Coffee & Snacks',
    type: 'EXPENSE',
    icon: 'coffee',
    color: '#F59E0B',
    isDefault: true,
    description: 'Campus cafe, boba, study snacks & energy drinks'
  },
  {
    id: 'cat-exp-3',
    name: 'Books & Supplies',
    type: 'EXPENSE',
    icon: 'book-open',
    color: '#38BDF8',
    isDefault: true,
    description: 'Course textbooks, lab gear, stationery & printing'
  },
  {
    id: 'cat-exp-4',
    name: 'Housing & Utilities',
    type: 'EXPENSE',
    icon: 'home',
    color: '#A78BFA',
    isDefault: true,
    description: 'Dorm rent, utilities, laundry & room essentials'
  },
  {
    id: 'cat-exp-5',
    name: 'Transport & Commute',
    type: 'EXPENSE',
    icon: 'bus',
    color: '#00F5A0',
    isDefault: true,
    description: 'Campus shuttle, bus pass, bike repair, ride-sharing'
  },
  {
    id: 'cat-exp-6',
    name: 'Entertainment & Social',
    type: 'EXPENSE',
    icon: 'gamepad-2',
    color: '#FF6B6B',
    isDefault: true,
    description: 'Cinema, campus concerts, student clubs & outings'
  },
  {
    id: 'cat-exp-7',
    name: 'Tech & Subscriptions',
    type: 'EXPENSE',
    icon: 'laptop',
    color: '#FB7185',
    isDefault: true,
    description: 'Spotify, Notion, cloud storage, software tools'
  },
  {
    id: 'cat-exp-8',
    name: 'Health & Fitness',
    type: 'EXPENSE',
    icon: 'heart-pulse',
    color: '#34D399',
    isDefault: true,
    description: 'Campus gym pass, pharmacy, vitamins & sports'
  },

  // Income Categories (Default System)
  {
    id: 'cat-inc-1',
    name: 'Part-time Job',
    type: 'INCOME',
    icon: 'briefcase',
    color: '#00F5A0',
    isDefault: true,
    description: 'Library assistant, lab monitor or campus retail shifts'
  },
  {
    id: 'cat-inc-2',
    name: 'Scholarship & Grants',
    type: 'INCOME',
    icon: 'award',
    color: '#FFE600',
    isDefault: true,
    description: 'Academic merit, faculty stipend & research assistance'
  },
  {
    id: 'cat-inc-3',
    name: 'Family Allowance',
    type: 'INCOME',
    icon: 'gift',
    color: '#38BDF8',
    isDefault: true,
    description: 'Monthly student living support from family'
  },
  {
    id: 'cat-inc-4',
    name: 'Freelance & Tutoring',
    type: 'INCOME',
    icon: 'code',
    color: '#A78BFA',
    isDefault: true,
    description: 'Peer tutoring, graphic design, programming gigs'
  },

  // User-created categories (Can be edited/deleted)
  {
    id: 'cat-usr-1',
    name: 'Hackathon Travel',
    type: 'EXPENSE',
    icon: 'plane',
    color: '#EC4899',
    isDefault: false,
    userId: 'user-001',
    description: 'Travel tickets and entry for university hackathons'
  }
];
