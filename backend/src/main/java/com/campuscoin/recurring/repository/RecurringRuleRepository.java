package com.campuscoin.recurring.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.recurring.entity.RecurringRule;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    @Query("""
            SELECT r FROM RecurringRule r
            JOIN FETCH r.category
             WHERE r.userId = :userId
             ORDER BY r.nextRunDate ASC, r.id ASC
            """)
    List<RecurringRule> findForStudent(@Param("userId") Long userId);

    @Query("""
            SELECT r FROM RecurringRule r
            JOIN FETCH r.category
             WHERE r.id = :id AND r.userId = :userId
            """)
    Optional<RecurringRule> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM RecurringRule r
             WHERE r.id = :id AND r.userId = :userId
            """)
    Optional<RecurringRule> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                       @Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM transactions WHERE recurring_rule_id = :ruleId",
            nativeQuery = true)
    long countTransactionsGeneratedBy(@Param("ruleId") Long ruleId);
}
