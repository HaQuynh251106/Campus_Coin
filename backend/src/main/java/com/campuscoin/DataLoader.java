package com.campuscoin;

import com.campuscoin.model.entity.Merchant;
import com.campuscoin.model.entity.Transaction;
import com.campuscoin.model.entity.User;
import com.campuscoin.model.entity.Wallet;
import com.campuscoin.model.enums.Role;
import com.campuscoin.model.enums.TransactionStatus;
import com.campuscoin.model.enums.TransactionType;
import com.campuscoin.model.enums.WalletStatus;
import com.campuscoin.repository.MerchantRepository;
import com.campuscoin.repository.TransactionRepository;
import com.campuscoin.repository.UserRepository;
import com.campuscoin.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.count() > 0) {
            return;
        }

        log.info("--- Initializing Campus Coin Seed Data ---");

        // 1. Create Users (Students & Faculty)
        User student1 = userRepository.save(User.builder()
                .studentId("SV001")
                .fullName("Nguyen Van An")
                .email("an.nguyen@campuscoin.edu.vn")
                .phone("0901234567")
                .department("Khoa Cong nghe Thong tin")
                .role(Role.STUDENT)
                .avatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=AnNguyen")
                .build());

        User student2 = userRepository.save(User.builder()
                .studentId("SV002")
                .fullName("Tran Thi Mai")
                .email("mai.tran@campuscoin.edu.vn")
                .phone("0912345678")
                .department("Khoa Kinh te & Quan tri")
                .role(Role.STUDENT)
                .avatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=MaiTran")
                .build());

        User student3 = userRepository.save(User.builder()
                .studentId("SV003")
                .fullName("Le Hoang Long")
                .email("long.le@campuscoin.edu.vn")
                .phone("0923456789")
                .department("Khoa Dien tu Vien thong")
                .role(Role.STUDENT)
                .avatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=LongLe")
                .build());

        User lecturer = userRepository.save(User.builder()
                .studentId("GV001")
                .fullName("TS. Pham Quoc Hung")
                .email("hung.pham@campuscoin.edu.vn")
                .phone("0934567890")
                .department("Khoa Cong nghe Thong tin")
                .role(Role.LECTURER)
                .avatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=HungPham")
                .build());

        // Merchant user accounts
        User canteenUser = userRepository.save(User.builder()
                .studentId("M001")
                .fullName("Canteen Khu A")
                .email("canteen.a@campuscoin.edu.vn")
                .phone("0945678901")
                .department("Dich vu Sinh vien")
                .role(Role.MERCHANT)
                .avatarUrl("https://api.dicebear.com/7.x/bottts/svg?seed=Canteen")
                .build());

        User cafeUser = userRepository.save(User.builder()
                .studentId("M002")
                .fullName("Campus Coffee")
                .email("cafe@campuscoin.edu.vn")
                .phone("0945678902")
                .department("Dich vu Sinh vien")
                .role(Role.MERCHANT)
                .avatarUrl("https://api.dicebear.com/7.x/bottts/svg?seed=Cafe")
                .build());

        User bookstoreUser = userRepository.save(User.builder()
                .studentId("M003")
                .fullName("Hieu Sach Dai Hoc")
                .email("bookstore@campuscoin.edu.vn")
                .phone("0945678903")
                .department("Dich vu Sinh vien")
                .role(Role.MERCHANT)
                .avatarUrl("https://api.dicebear.com/7.x/bottts/svg?seed=Bookstore")
                .build());

        User libraryUser = userRepository.save(User.builder()
                .studentId("M004")
                .fullName("Thu Vien & In An")
                .email("library@campuscoin.edu.vn")
                .phone("0945678904")
                .department("Dich vu Sinh vien")
                .role(Role.MERCHANT)
                .avatarUrl("https://api.dicebear.com/7.x/bottts/svg?seed=Library")
                .build());

        // 2. Create Wallets
        Wallet wallet1 = walletRepository.save(Wallet.builder()
                .user(student1)
                .balance(new BigDecimal("250.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet wallet2 = walletRepository.save(Wallet.builder()
                .user(student2)
                .balance(new BigDecimal("180.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet wallet3 = walletRepository.save(Wallet.builder()
                .user(student3)
                .balance(new BigDecimal("320.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet walletLecturer = walletRepository.save(Wallet.builder()
                .user(lecturer)
                .balance(new BigDecimal("500.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet walletCanteen = walletRepository.save(Wallet.builder()
                .user(canteenUser)
                .balance(new BigDecimal("5400.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet walletCafe = walletRepository.save(Wallet.builder()
                .user(cafeUser)
                .balance(new BigDecimal("3200.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet walletBookstore = walletRepository.save(Wallet.builder()
                .user(bookstoreUser)
                .balance(new BigDecimal("2100.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        Wallet walletLibrary = walletRepository.save(Wallet.builder()
                .user(libraryUser)
                .balance(new BigDecimal("1800.00"))
                .currency("CCOIN")
                .status(WalletStatus.ACTIVE)
                .build());

        // 3. Create Merchants
        merchantRepository.save(Merchant.builder()
                .name("Canteen Khu A - Mon ngon sinh vien")
                .category("Am thuc")
                .location("Toa nha A, Tang tret")
                .merchantCode("CANTEEN_A")
                .wallet(walletCanteen)
                .contactEmail("canteen.a@campuscoin.edu.vn")
                .build());

        merchantRepository.save(Merchant.builder()
                .name("Campus Coffee & Bakery")
                .category("Do uong & Cafe")
                .location("San trung tam, Canh thu vien")
                .merchantCode("CAFE_CAMPUS")
                .wallet(walletCafe)
                .contactEmail("cafe@campuscoin.edu.vn")
                .build());

        merchantRepository.save(Merchant.builder()
                .name("Hieu sach & Van phong pham Dai hoc")
                .category("Sach & Do dung hoc tap")
                .location("Toa nha B, Tang 1")
                .merchantCode("BOOKSTORE")
                .wallet(walletBookstore)
                .contactEmail("bookstore@campuscoin.edu.vn")
                .build());

        merchantRepository.save(Merchant.builder()
                .name("Trung tam Thu vien & In an Photo")
                .category("Dich vu in an")
                .location("Toa nha Thu vien, Phong 102")
                .merchantCode("PRINT_LIBRARY")
                .wallet(walletLibrary)
                .contactEmail("library@campuscoin.edu.vn")
                .build());

        // 4. Sample Transactions
        transactionRepository.save(Transaction.builder()
                .transactionCode("TX-INIT-001")
                .senderWallet(null)
                .receiverWallet(wallet1)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.REWARD)
                .status(TransactionStatus.SUCCESS)
                .description("Phan thuong chao mung tan sinh vien K49")
                .createdAt(LocalDateTime.now().minusDays(3))
                .build());

        transactionRepository.save(Transaction.builder()
                .transactionCode("TX-INIT-002")
                .senderWallet(wallet1)
                .receiverWallet(walletCanteen)
                .amount(new BigDecimal("25.00"))
                .type(TransactionType.PURCHASE)
                .status(TransactionStatus.SUCCESS)
                .description("Thanh toan Com trua tai Canteen Nha A")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());

        transactionRepository.save(Transaction.builder()
                .transactionCode("TX-INIT-003")
                .senderWallet(wallet2)
                .receiverWallet(wallet1)
                .amount(new BigDecimal("15.00"))
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.SUCCESS)
                .description("Chia tien photo tai lieu mon He dieu hanh")
                .createdAt(LocalDateTime.now().minusHours(4))
                .build());

        log.info("--- Campus Coin Seed Data Loaded Successfully! ---");
    }
}
