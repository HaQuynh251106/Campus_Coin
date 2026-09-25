export type UserRole = 'STUDENT' | 'ADMIN';

export type FontSizePreference = 'small' | 'medium' | 'large';

export interface UserSettings {
  darkMode: boolean;
  fontSize: FontSizePreference;
  currency: string;
}

export interface UserSummary {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

export interface ProfileResponse {
  id: number;
  fullName: string;
  email: string;
  academicYear: string | null;
  monthlyAllowanceBaseline: number;
  monthlySavingsGoal: number;
  currency: string;
  themePreference: 'LIGHT' | 'DARK' | 'SYSTEM';
  fontScale: 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';
}

export interface User {
  id: string | number;
  studentId?: string;
  name: string;
  email: string;
  avatar?: string;
  role: UserRole;
  university?: string;
  major?: string;
  academicYear?: string;
  monthlyAllowance?: number;
  savingsGoal?: number;
  settings?: UserSettings;
  status?: 'ACTIVE' | 'DISABLED';
  joinedDate?: string;
}
