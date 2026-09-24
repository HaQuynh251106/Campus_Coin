import { User } from '../core/models/user.model';

export const MOCK_USERS: User[] = [
  {
    id: 'user-001',
    studentId: 'SV2026-001',
    name: 'Alex Morgan',
    email: 'alex.morgan@campus.edu',
    // Real freely-licensed Unsplash portrait photo
    avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80',
    role: 'STUDENT',
    university: 'State University of Technology',
    major: 'Computer Science',
    academicYear: 'Junior (3rd Year)',
    monthlyAllowance: 650,
    savingsGoal: 1500,
    settings: {
      darkMode: false,
      fontSize: 'medium',
      currency: '$'
    },
    status: 'ACTIVE',
    joinedDate: '2025-09-01'
  },
  {
    id: 'user-002',
    studentId: 'SV2026-042',
    name: 'Marcus Chen',
    email: 'marcus.chen@campus.edu',
    // Real freely-licensed Unsplash portrait photo
    avatar: 'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?auto=format&fit=crop&w=256&q=80',
    role: 'STUDENT',
    university: 'State University of Technology',
    major: 'Business Administration',
    academicYear: 'Sophomore (2nd Year)',
    monthlyAllowance: 500,
    savingsGoal: 1200,
    settings: {
      darkMode: false,
      fontSize: 'medium',
      currency: '$'
    },
    status: 'ACTIVE',
    joinedDate: '2025-10-15'
  },
  {
    id: 'user-003',
    studentId: 'SV2026-088',
    name: 'Linh Nguyen',
    email: 'linh.nguyen@campus.edu',
    // Real freely-licensed Unsplash portrait photo
    avatar: 'https://images.unsplash.com/photo-1517841905240-472988babdf9?auto=format&fit=crop&w=256&q=80',
    role: 'STUDENT',
    university: 'State University of Technology',
    major: 'Graphic Design',
    academicYear: 'Senior (4th Year)',
    monthlyAllowance: 700,
    savingsGoal: 2000,
    settings: {
      darkMode: false,
      fontSize: 'medium',
      currency: '$'
    },
    status: 'ACTIVE',
    joinedDate: '2024-08-20'
  },
  {
    id: 'user-004',
    studentId: 'SV2026-104',
    name: 'Sarah Jenkins',
    email: 'sarah.j@campus.edu',
    // Real freely-licensed Unsplash portrait photo
    avatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=256&q=80',
    role: 'STUDENT',
    university: 'State University of Technology',
    major: 'Biochemistry',
    academicYear: 'Freshman (1st Year)',
    monthlyAllowance: 450,
    savingsGoal: 800,
    settings: {
      darkMode: false,
      fontSize: 'medium',
      currency: '$'
    },
    status: 'DISABLED',
    joinedDate: '2026-01-10'
  },
  {
    id: 'admin-001',
    studentId: 'ADMIN-ROOT',
    name: 'Dr. Robert Vance',
    email: 'admin@campuscoin.edu',
    // Real freely-licensed Unsplash portrait photo
    avatar: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&w=256&q=80',
    role: 'ADMIN',
    university: 'Campus Coin Administration',
    major: 'Student Financial Services',
    academicYear: 'Staff / Faculty',
    monthlyAllowance: 0,
    savingsGoal: 0,
    settings: {
      darkMode: false,
      fontSize: 'medium',
      currency: '$'
    },
    status: 'ACTIVE',
    joinedDate: '2024-01-01'
  }
];
