package com.campuscoin.tips.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.tips.entity.UserTip;

import jakarta.persistence.LockModeType;

public interface UserTipRepository extends JpaRepository<UserTip, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM UserTip t
             WHERE t.id = :id AND t.userId = :userId
            """)
    Optional<UserTip> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(t) FROM UserTip t
             WHERE t.userId = :userId AND t.periodMonth = :periodMonth
            """)
    long countForMonth(@Param("userId") Long userId, @Param("periodMonth") LocalDate periodMonth);
}
