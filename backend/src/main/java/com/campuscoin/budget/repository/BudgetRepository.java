package com.campuscoin.budget.repository;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.budget.entity.Budget;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    @Query("""
            SELECT b FROM Budget b
             WHERE b.id = :id AND b.userId = :userId
            """)
    Optional<Budget> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM Budget b
             WHERE b.id = :id AND b.userId = :userId
            """)
    Optional<Budget> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                @Param("userId") Long userId);

    @Query("""
            SELECT b.id FROM Budget b
             WHERE b.userId = :userId
               AND b.category.id = :categoryId
               AND b.periodMonth = :periodMonth
            """)
    Optional<Long> findExistingId(@Param("userId") Long userId,
                                  @Param("categoryId") Long categoryId,
                                  @Param("periodMonth") LocalDate periodMonth);
}
