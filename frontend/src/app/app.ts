import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CampusCoinService } from './services/campus-coin.service';
import {
  User,
  Wallet,
  Transaction,
  Merchant,
  TransferRequest,
  TopupRequest,
  PaymentRequest
} from './models/campus-coin.models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  private api = inject(CampusCoinService);

  // App State
  backendOnline = false;
  backendInfo: any = null;
  loading = false;
  errorMessage = '';
  successMessage = '';

  users: User[] = [];
  merchants: Merchant[] = [];
  currentStudentId = 'SV001';
  currentUser: User | null = null;
  currentWallet: Wallet | null = null;
  transactions: Transaction[] = [];

  // Modals
  activeModal: 'transfer' | 'topup' | 'pay' | null = null;

  transferData: TransferRequest = {
    senderStudentId: 'SV001',
    receiverStudentId: 'SV002',
    amount: 20,
    description: 'Chuyển tiền ăn trưa'
  };

  topupData: TopupRequest = {
    studentId: 'SV001',
    amount: 100,
    paymentMethod: 'MoMo'
  };

  payData: PaymentRequest = {
    studentId: 'SV001',
    merchantCode: 'CANTEEN_A',
    amount: 30,
    description: 'Bữa trưa Canteen'
  };

  ngOnInit(): void {
    this.checkBackendHealth();
    this.loadInitialData();
  }

  checkBackendHealth(): void {
    this.api.getHealth().subscribe({
      next: (data) => {
        this.backendOnline = true;
        this.backendInfo = data;
      },
      error: () => {
        this.backendOnline = false;
      }
    });
  }

  loadInitialData(): void {
    this.loading = true;
    this.api.getUsers().subscribe({
      next: (users) => {
        this.users = users;
        this.switchUser(this.currentStudentId);
      },
      error: (err) => {
        console.error('Failed to load users:', err);
        this.loading = false;
      }
    });

    this.api.getMerchants().subscribe({
      next: (merchants) => {
        this.merchants = merchants;
      },
      error: (err) => console.error('Failed to load merchants:', err)
    });
  }

  switchUser(studentId: string): void {
    this.currentStudentId = studentId;
    this.currentUser = this.users.find(u => u.studentId === studentId) || null;
    this.transferData.senderStudentId = studentId;
    this.topupData.studentId = studentId;
    this.payData.studentId = studentId;

    // Pick a default receiver different from current user
    const otherUser = this.users.find(u => u.studentId !== studentId && u.role === 'STUDENT');
    if (otherUser) {
      this.transferData.receiverStudentId = otherUser.studentId;
    }

    this.refreshWalletAndTransactions();
  }

  refreshWalletAndTransactions(): void {
    this.loading = true;
    this.api.getWallet(this.currentStudentId).subscribe({
      next: (wallet) => {
        this.currentWallet = wallet;
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load wallet:', err);
        this.loading = false;
      }
    });

    this.api.getStudentTransactions(this.currentStudentId).subscribe({
      next: (txs) => {
        this.transactions = txs;
      },
      error: (err) => console.error('Failed to load transactions:', err)
    });
  }

  openModal(type: 'transfer' | 'topup' | 'pay', merchantCode?: string): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.activeModal = type;

    if (type === 'pay' && merchantCode) {
      this.payData.merchantCode = merchantCode;
    }
  }

  closeModal(): void {
    this.activeModal = null;
    this.errorMessage = '';
  }

  submitTransfer(): void {
    if (!this.transferData.amount || this.transferData.amount <= 0) {
      this.errorMessage = 'Vui lòng nhập số lượng coin hợp lệ';
      return;
    }

    this.loading = true;
    this.api.transferCoins(this.transferData).subscribe({
      next: (res) => {
        this.showSuccess(`Đã chuyển thành công ${this.transferData.amount} CCOIN!`);
        this.closeModal();
        this.refreshWalletAndTransactions();
      },
      error: (err) => {
        this.errorMessage = err.error?.message || 'Giao dịch chuyển tiền thất bại';
        this.loading = false;
      }
    });
  }

  submitTopup(): void {
    if (!this.topupData.amount || this.topupData.amount <= 0) {
      this.errorMessage = 'Vui lòng nhập số coin nạp hợp lệ';
      return;
    }

    this.loading = true;
    this.api.topupCoins(this.topupData).subscribe({
      next: (res) => {
        this.showSuccess(`Nạp thành công ${this.topupData.amount} CCOIN vào ví!`);
        this.closeModal();
        this.refreshWalletAndTransactions();
      },
      error: (err) => {
        this.errorMessage = err.error?.message || 'Nạp coin thất bại';
        this.loading = false;
      }
    });
  }

  submitPayment(): void {
    if (!this.payData.amount || this.payData.amount <= 0) {
      this.errorMessage = 'Vui lòng nhập số coin thanh toán hợp lệ';
      return;
    }

    this.loading = true;
    this.api.payMerchant(this.payData).subscribe({
      next: (res) => {
        this.showSuccess(`Thanh toán thành công ${this.payData.amount} CCOIN!`);
        this.closeModal();
        this.refreshWalletAndTransactions();
      },
      error: (err) => {
        this.errorMessage = err.error?.message || 'Thanh toán thất bại';
        this.loading = false;
      }
    });
  }

  showSuccess(msg: string): void {
    this.successMessage = msg;
    setTimeout(() => {
      this.successMessage = '';
    }, 4000);
  }

  getReceiverCandidates(): User[] {
    return this.users.filter(u => u.studentId !== this.currentStudentId);
  }
}
