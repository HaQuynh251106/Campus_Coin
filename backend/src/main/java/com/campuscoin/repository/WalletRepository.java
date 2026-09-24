package com.campuscoin.repository;

import com.campuscoin.model.entity.User;
import com.campuscoin.model.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByUser(User user);
    Optional<Wallet> findByUserId(Long userId);
    Optional<Wallet> findByUserStudentId(String studentId);
}
