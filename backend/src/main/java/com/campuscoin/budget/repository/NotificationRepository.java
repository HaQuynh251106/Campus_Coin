package com.campuscoin.budget.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.budget.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            SELECT n FROM Notification n
             WHERE n.userId = :userId
             ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findForStudent(@Param("userId") Long userId);

    @Query("""
            SELECT n FROM Notification n
             WHERE n.id = :id AND n.userId = :userId
            """)
    Optional<Notification> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
