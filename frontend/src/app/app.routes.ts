import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { StudentLayoutComponent } from './layouts/student-layout/student-layout.component';
import { AdminLayoutComponent } from './layouts/admin-layout/admin-layout.component';
import { AuthLayoutComponent } from './layouts/auth-layout/auth-layout.component';

export const routes: Routes = [
  // Default root redirect
  {
    path: '',
    redirectTo: '/app/home',
    pathMatch: 'full'
  },

  // 1. Authentication Module
  {
    path: 'auth',
    component: AuthLayoutComponent,
    children: [
      {
        path: '',
        redirectTo: 'login',
        pathMatch: 'full'
      },
      {
        path: 'login',
        title: 'Student Login — Campus Coin',
        loadComponent: () =>
          import('./features/auth/login/login.component').then(m => m.LoginComponent)
      },
      {
        path: 'register',
        title: 'Student Signup — Campus Coin',
        loadComponent: () =>
          import('./features/auth/register/register.component').then(m => m.RegisterComponent)
      },
      {
        path: 'forgot-password',
        title: 'Recover Password — Campus Coin',
        loadComponent: () =>
          import('./features/auth/forgot-password/forgot-password.component').then(m => m.ForgotPasswordComponent)
      },
      {
        path: 'admin-login',
        title: 'Admin Portal Login — Campus Coin',
        loadComponent: () =>
          import('./features/auth/admin-login/admin-login.component').then(m => m.AdminLoginComponent)
      }
    ]
  },

  // 2. Student Portal Module (Responsive Nav Layout)
  {
    path: 'app',
    component: StudentLayoutComponent,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        redirectTo: 'home',
        pathMatch: 'full'
      },
      {
        path: 'home',
        title: 'Student Feed & Balance — Campus Coin',
        loadComponent: () =>
          import('./features/home-feed/home-feed.component').then(m => m.HomeFeedComponent)
      },
      {
        path: 'quick-add',
        title: 'Quick Add Spending — Campus Coin',
        loadComponent: () =>
          import('./features/quick-add/quick-add.component').then(m => m.QuickAddComponent)
      },
      {
        path: 'reports',
        title: 'Analytics & 6-Month Trend — Campus Coin',
        loadComponent: () =>
          import('./features/reports/reports.component').then(m => m.ReportsComponent)
      },
      {
        path: 'budgets',
        title: 'Category Budgets & Alerts — Campus Coin',
        loadComponent: () =>
          import('./features/budgets/budgets.component').then(m => m.BudgetsComponent)
      },
      {
        path: 'insights',
        title: 'AI Monthly Summaries & Tips — Campus Coin',
        loadComponent: () =>
          import('./features/insights/insights.component').then(m => m.InsightsComponent)
      },
      {
        path: 'categories',
        title: 'Manage Categories — Campus Coin',
        loadComponent: () =>
          import('./features/categories/categories.component').then(m => m.CategoriesComponent)
      },
      {
        path: 'profile',
        title: 'Profile, Dark Mode & CSV Import — Campus Coin',
        loadComponent: () =>
          import('./features/profile/profile.component').then(m => m.ProfileComponent)
      }
    ]
  },

  // 3. Admin Portal Module (Sidebar-Only Classic Dashboard Layout)
  {
    path: 'admin',
    component: AdminLayoutComponent,
    canActivate: [adminGuard],
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full'
      },
      {
        path: 'dashboard',
        title: 'Institutional Dashboard — Campus Coin Admin',
        loadComponent: () =>
          import('./features/admin/admin-dashboard/admin-dashboard.component').then(m => m.AdminDashboardComponent)
      },
      {
        path: 'users',
        title: 'User Management Directory — Campus Coin Admin',
        loadComponent: () =>
          import('./features/admin/admin-users/admin-users.component').then(m => m.AdminUsersComponent)
      },
      {
        path: 'categories',
        title: 'Default Categories & Tip Templates — Campus Coin Admin',
        loadComponent: () =>
          import('./features/admin/admin-categories/admin-categories.component').then(m => m.AdminCategoriesComponent)
      }
    ]
  },

  // 4. Stated Requirement: Visible Nested Sitemap Page
  {
    path: 'sitemap',
    title: 'Complete Sitemap & Route Index — Campus Coin',
    loadComponent: () =>
      import('./features/sitemap/sitemap.component').then(m => m.SitemapComponent)
  },

  // Fallback Wildcard
  {
    path: '**',
    redirectTo: '/app/home'
  }
];
