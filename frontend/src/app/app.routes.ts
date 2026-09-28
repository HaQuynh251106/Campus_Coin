import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { StudentLayoutComponent } from './layouts/student-layout/student-layout.component';
import { AdminLayoutComponent } from './layouts/admin-layout/admin-layout.component';
import { AuthLayoutComponent } from './layouts/auth-layout/auth-layout.component';

export const routes: Routes = [

  {
    path: '',
    pathMatch: 'full',
    title: 'Campus Coin',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/landing/landing.component').then(m => m.LandingComponent)
  },

  {
    path: 'sitemap',
    title: 'Sitemap — Campus Coin',
    loadComponent: () =>
      import('./features/sitemap/sitemap.component').then(m => m.SitemapComponent)
  },

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
        title: 'Sign In — Campus Coin',
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
        redirectTo: 'login',
        pathMatch: 'full'
      }
    ]
  },

  {
    path: 'reset-password',
    redirectTo: 'auth/forgot-password'
  },

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
        path: 'categories',
        title: 'Manage Categories — Campus Coin',
        loadComponent: () =>
          import('./features/categories/categories.component').then(m => m.CategoriesComponent)
      },
      {
        path: 'recurring',
        title: 'Recurring Rules — Campus Coin',
        loadComponent: () =>
          import('./features/recurring/recurring.component').then(m => m.RecurringComponent)
      },
      {
        path: 'tips',
        title: 'Saving Tips — Campus Coin',
        loadComponent: () =>
          import('./features/tips/tips.component').then(m => m.TipsComponent)
      },
      {
        path: 'bookmarks',
        title: 'Saved Tips — Campus Coin',
        loadComponent: () =>
          import('./features/bookmarks/bookmarks.component').then(m => m.BookmarksComponent)
      },
      {
        path: 'profile',
        title: 'Profile, Dark Mode & Preferences — Campus Coin',
        loadComponent: () =>
          import('./features/profile/profile.component').then(m => m.ProfileComponent)
      },

      {
        path: 'imports',
        title: 'Import CSV — Campus Coin',
        loadComponent: () =>
          import('./features/imports/csv-import.component').then(m => m.CsvImportComponent)
      },
      {
        path: 'insights',
        title: 'Monthly Insights — Campus Coin',
        loadComponent: () =>
          import('./features/insights/insights.component').then(m => m.InsightsComponent)
      },
      {
        path: 'anomalies',
        title: 'Anomaly Check — Campus Coin',
        loadComponent: () =>
          import('./features/anomalies/anomalies.component').then(m => m.AnomaliesComponent)
      },
      {
        path: 'forecast',
        title: 'Next Month Forecast — Campus Coin',
        loadComponent: () =>
          import('./features/forecast/forecast.component').then(m => m.ForecastComponent)
      },
      {
        path: 'recent-activity',
        title: 'Recent Activity — Campus Coin',
        loadComponent: () =>
          import('./features/recent-activity/recent-activity.component').then(
            m => m.RecentActivityComponent
          )
      }
    ]
  },

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

  {
    path: '**',
    redirectTo: ''
  }
];
