export interface AdminUser {
  id: number;
  fullName: string;
  email: string;
  role: 'STUDENT' | 'ADMIN';
  status: 'ACTIVE' | 'DISABLED';
  academicYear?: string | null;
  createdAt: string;
  lastLoginAt?: string | null;
}

export interface AdminAnnouncement {
  id: number;
  title: string;
  body: string;
  audience: 'ALL' | 'STUDENTS' | 'ADMINS';
  severity: 'INFO' | 'WARNING' | 'CRITICAL' | 'SUCCESS';
  startsAt: string;
  endsAt?: string | null;
  isActive: boolean;
  createdBy?: number | null;
  createdAt: string;
}

export interface AdminTipTemplate {
  id: number;
  code: string;
  titleTemplate: string;
  bodyTemplate: string;
  conditionType: string;
  defaultPriority: number;
  isActive: boolean;
  createdBy?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface SystemSetting {
  key: string;
  value: string;
  valueType: 'STRING' | 'INT' | 'DECIMAL' | 'BOOLEAN' | 'JSON';
  description?: string;
  adjustable: boolean;
}

export interface AdminUsageStats {
  totalStudents: number;
  activeStudents: number;
  disabledStudents: number;
  activeUsers30d: number;
  totalTransactions: number;
  totalExpenseLogged: number;
  totalIncomeLogged: number;
  totalBudgets: number;
  totalTipsGenerated: number;
  totalInsightsGenerated: number;
}

export interface AdminTopCategory {
  categoryId: number;
  categoryName: string;
  type: 'INCOME' | 'EXPENSE';
  scope: 'DEFAULT' | 'PERSONAL';
  txnCount: number;
  totalAmount: number;
  distinctUsers: number;
}
