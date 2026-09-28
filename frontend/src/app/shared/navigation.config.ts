export interface NavItem {
  label: string;
  route: string;
  icon: string;
  badge?: string;
  isEmphasized?: boolean;
}

export const STUDENT_NAV_ITEMS: NavItem[] = [
  { label: 'Feed', route: '/app/home', icon: 'home' },
  { label: 'Reports', route: '/app/reports', icon: 'reports' },
  { label: 'Quick Add', route: '/app/quick-add', icon: 'plus', isEmphasized: true },
  { label: 'Budgets', route: '/app/budgets', icon: 'budgets' },
  { label: 'Profile', route: '/app/profile', icon: 'profile' }
];

export const STUDENT_SIDEBAR_EXTRA_ITEMS: NavItem[] = [
  { label: 'Categories', route: '/app/categories', icon: 'categories' },
  { label: 'Recurring', route: '/app/recurring', icon: 'repeat' },
  { label: 'Tips', route: '/app/tips', icon: 'insights' },

  { label: 'Import CSV', route: '/app/imports', icon: 'upload' },
  { label: 'Monthly Insights', route: '/app/insights', icon: 'sparkles' },
  { label: 'Anomaly Check', route: '/app/anomalies', icon: 'search' },
  { label: 'Forecast', route: '/app/forecast', icon: 'trending-up' },
  { label: 'Recent Activity', route: '/app/recent-activity', icon: 'home-sub' }
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', route: '/admin/dashboard', icon: 'reports' },
  { label: 'User Directory', route: '/admin/users', icon: 'users' },
  { label: 'Categories & Tips', route: '/admin/categories', icon: 'categories' }
];
