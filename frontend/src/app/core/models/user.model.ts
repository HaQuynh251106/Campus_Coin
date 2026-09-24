export type UserRole = 'STUDENT' | 'ADMIN';

export type FontSizePreference = 'small' | 'medium' | 'large';

export interface UserSettings {
  darkMode: boolean;
  fontSize: FontSizePreference;
  currency: string;
}

export interface User {
  id: string;
  studentId: string;
  name: string;
  email: string;
  avatar: string;
  role: UserRole;
  university: string;
  major: string;
  academicYear: string;
  monthlyAllowance: number;
  savingsGoal: number;
  settings: UserSettings;
  status: 'ACTIVE' | 'DISABLED';
  joinedDate: string;
}
