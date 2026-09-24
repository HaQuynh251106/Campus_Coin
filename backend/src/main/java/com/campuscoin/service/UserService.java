package com.campuscoin.service;

import com.campuscoin.model.entity.User;
import com.campuscoin.model.entity.Wallet;
import com.campuscoin.model.enums.Role;
import com.campuscoin.repository.UserRepository;
import com.campuscoin.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByStudentId(String studentId) {
        return userRepository.findByStudentId(studentId);
    }

    @Transactional
    public User createUser(User user, BigDecimal initialBalance) {
        if (userRepository.existsByStudentId(user.getStudentId())) {
            throw new IllegalArgumentException("Student ID already exists: " + user.getStudentId());
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + user.getEmail());
        }

        User savedUser = userRepository.save(user);

        // Auto-create wallet for user
        Wallet wallet = Wallet.builder()
                .user(savedUser)
                .balance(initialBalance != null ? initialBalance : BigDecimal.valueOf(100.00)) // 100 free welcome coins
                .build();
        walletRepository.save(wallet);

        return savedUser;
    }
}
