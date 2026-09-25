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
  { label: 'Sitemap', route: '/sitemap', icon: 'map' }
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', route: '/admin/dashboard', icon: 'reports' },
  { label: 'User Directory', route: '/admin/users', icon: 'users' },
  { label: 'Categories & Tips', route: '/admin/categories', icon: 'categories' },
  { label: 'System Sitemap', route: '/sitemap', icon: 'map' }
];
