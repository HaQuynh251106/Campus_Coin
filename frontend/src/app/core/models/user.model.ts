export type UserRole = 'STUDENT' | 'ADMIN';

/** What the UI offers: the three sizes the stylesheet actually defines. */
export type FontSizePreference = 'small' | 'medium' | 'large';

/** What UC-27 accepts and the server stores. There are four, one more than the stylesheet has rules for. */
export type ServerFontScale = 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';

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
  fontScale: ServerFontScale;
}

/**
 * The signed-in account as the client holds it.
 *
 * There is no `major`: the profile contract has no such field, and neither does the schema. It was
 * previously carried here and offered on two forms, where it could be typed into but never saved.
 */
export interface User {
  id: string | number;
  studentId?: string;
  name: string;
  email: string;
  avatar?: string;
  role: UserRole;
  university?: string;
  academicYear?: string;
  monthlyAllowance?: number;
  savingsGoal?: number;
  settings?: UserSettings;
  status?: 'ACTIVE' | 'DISABLED';
  joinedDate?: string;
}
