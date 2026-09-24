export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
}

export type Role = 'STUDENT' | 'LECTURER' | 'ADMIN' | 'MERCHANT';
export type TransactionType = 'TOPUP' | 'TRANSFER' | 'PURCHASE' | 'REWARD' | 'REFUND';
export type TransactionStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
export type WalletStatus = 'ACTIVE' | 'LOCKED' | 'SUSPENDED';

export interface User {
  id: number;
  studentId: string;
  fullName: string;
  email: string;
  phone: string;
  role: Role;
  avatarUrl: string;
  department: string;
  createdAt: string;
}

export interface Wallet {
  id: number;
  studentId: string;
  ownerName: string;
  email: string;
  balance: number;
  currency: string;
  status: WalletStatus;
  updatedAt: string;
}

export interface Transaction {
  id: number;
  transactionCode: string;
  senderName: string;
  senderStudentId: string;
  receiverName: string;
  receiverStudentId: string;
  amount: number;
  type: TransactionType;
  status: TransactionStatus;
  description: string;
  createdAt: string;
}

export interface Merchant {
  id: number;
  name: string;
  category: string;
  location: string;
  merchantCode: string;
  logoUrl?: string;
  contactEmail: string;
}

export interface TransferRequest {
  senderStudentId: string;
  receiverStudentId: string;
  amount: number;
  description?: string;
}

export interface TopupRequest {
  studentId: string;
  amount: number;
  paymentMethod: string;
}

export interface PaymentRequest {
  studentId: string;
  merchantCode: string;
  amount: number;
  description?: string;
}
