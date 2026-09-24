import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  ApiResponse,
  User,
  Wallet,
  Transaction,
  Merchant,
  TransferRequest,
  TopupRequest,
  PaymentRequest
} from '../models/campus-coin.models';

@Injectable({
  providedIn: 'root'
})
export class CampusCoinService {
  private http = inject(HttpClient);
  private baseUrl = '/api';

  getHealth(): Observable<any> {
    return this.http.get<ApiResponse<any>>(`${this.baseUrl}/health`).pipe(
      map(res => res.data)
    );
  }

  getUsers(): Observable<User[]> {
    return this.http.get<ApiResponse<User[]>>(`${this.baseUrl}/users`).pipe(
      map(res => res.data)
    );
  }

  getUser(studentId: string): Observable<User> {
    return this.http.get<ApiResponse<User>>(`${this.baseUrl}/users/${studentId}`).pipe(
      map(res => res.data)
    );
  }

  getWallets(): Observable<Wallet[]> {
    return this.http.get<ApiResponse<Wallet[]>>(`${this.baseUrl}/wallets`).pipe(
      map(res => res.data)
    );
  }

  getWallet(studentId: string): Observable<Wallet> {
    return this.http.get<ApiResponse<Wallet>>(`${this.baseUrl}/wallets/${studentId}`).pipe(
      map(res => res.data)
    );
  }

  getTransactions(): Observable<Transaction[]> {
    return this.http.get<ApiResponse<Transaction[]>>(`${this.baseUrl}/transactions`).pipe(
      map(res => res.data)
    );
  }

  getStudentTransactions(studentId: string): Observable<Transaction[]> {
    return this.http.get<ApiResponse<Transaction[]>>(`${this.baseUrl}/transactions/student/${studentId}`).pipe(
      map(res => res.data)
    );
  }

  getMerchants(): Observable<Merchant[]> {
    return this.http.get<ApiResponse<Merchant[]>>(`${this.baseUrl}/merchants`).pipe(
      map(res => res.data)
    );
  }

  transferCoins(payload: TransferRequest): Observable<ApiResponse<Transaction>> {
    return this.http.post<ApiResponse<Transaction>>(`${this.baseUrl}/transactions/transfer`, payload);
  }

  topupCoins(payload: TopupRequest): Observable<ApiResponse<Transaction>> {
    return this.http.post<ApiResponse<Transaction>>(`${this.baseUrl}/transactions/topup`, payload);
  }

  payMerchant(payload: PaymentRequest): Observable<ApiResponse<Transaction>> {
    return this.http.post<ApiResponse<Transaction>>(`${this.baseUrl}/transactions/pay`, payload);
  }
}
