package com.campuscoin.repository;

import com.campuscoin.model.entity.Transaction;
import com.campuscoin.model.entity.Wallet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByTransactionCode(String transactionCode);

    @Query("SELECT t FROM Transaction t WHERE t.senderWallet = :wallet OR t.receiverWallet = :wallet ORDER BY t.createdAt DESC")
    List<Transaction> findAllByWallet(@Param("wallet") Wallet wallet);

    @Query("SELECT t FROM Transaction t WHERE t.senderWallet = :wallet OR t.receiverWallet = :wallet ORDER BY t.createdAt DESC")
    Page<Transaction> findAllByWalletPaginated(@Param("wallet") Wallet wallet, Pageable pageable);
}
