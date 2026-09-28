package com.campuscoin.transaction.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.transaction.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("""
            SELECT t FROM Transaction t
            JOIN FETCH t.category
             WHERE t.userId = :userId
               AND (t.isDeleted = false OR :includeDeleted = true)
               AND t.txnDate >= :from
               AND t.txnDate <= :to
             ORDER BY t.txnDate DESC, t.id DESC
            """)
    List<Transaction> findForStudent(@Param("userId") Long userId,
                                     @Param("includeDeleted") boolean includeDeleted,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query("""
            SELECT t FROM Transaction t
            JOIN FETCH t.category
             WHERE t.id = :id AND t.userId = :userId AND t.isDeleted = false
            """)
    Optional<Transaction> findActiveByIdAndUserId(@Param("id") Long id,
                                                  @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.id = :id AND t.userId = :userId AND t.isDeleted = false
            """)
    Optional<Transaction> findActiveByIdAndUserIdForUpdate(@Param("id") Long id,
                                                           @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM Transaction t
             WHERE t.id = :id AND t.userId = :userId
            """)
    Optional<Transaction> findAnyByIdAndUserIdForUpdate(@Param("id") Long id,
                                                        @Param("userId") Long userId);
}
