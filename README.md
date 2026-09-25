# 🪙 Campus Coin - University Digital Wallet & Rewards System

Hệ thống ví điện tử và điểm thưởng số dành cho khuôn viên trường đại học (Campus Coin), hỗ trợ thanh toán căng tin, thư viện, dịch vụ photo/in ấn, chuyển tiền P2P giữa sinh viên và giảng viên, nạp coin.

---

## 🏗 Kiến Trúc Hệ Thống (Tech Stack)

### 1. Backend (Java Spring Boot)
- **Ngôn ngữ**: Java 21 LTS (Temurin JDK 21)
- **Framework**: Spring Boot 3.4.3
- **ORM & Database**: Spring Data JPA, Hibernate ORM
- **Database phát triển**: H2 In-Memory Database (zero-config, chạy ngay không cần cài đặt DB)
- **Database sản xuất**: PostgreSQL 16 (có sẵn `docker-compose.yml`)
- **API Documentation**: Springdoc OpenAPI / Swagger UI 3.0
- **DevTools**: Lombok, Spring Boot DevTools, Actuator

### 2. Frontend (Angular)
- **Framework**: Angular 21 (Standalone Components & Signals)
- **Language**: TypeScript 5.9
- **Style**: SCSS với hệ thống Design Token hiện đại, Responsive
- **HTTP & Routing**: Angular HttpClient với `proxy.conf.json` chuyển tiếp tự động sang Spring Boot backend (`http://localhost:8080`)

### 3. Tích Hợp VS Code & Môi Trường
- Tự động cấu hình `.vscode/launch.json` để debug F5 (Spring Boot + Angular Chrome Debug)
- Tự động cấu hình `.vscode/tasks.json` để chạy Backend, Frontend và Docker Compose
- Workspace file `campus-coin.code-workspace` đa thư mục
- Đã cài đặt các Extension VS Code cần thiết:
  - `vscjava.vscode-java-pack` (Extension Pack for Java)
  - `vmware.vscode-boot-dev-pack` (Spring Boot Extension Pack)
  - `angular.ng-template` (Angular Language Service)

---

## 📁 Cấu Trúc Dự Án

```
campus-coin/
├── backend/                        # Java Spring Boot API
│   ├── src/main/java/com/campuscoin/
│   │   ├── config/                 # CORS & Swagger Configuration
│   │   ├── controller/             # REST Controllers (Wallet, Transaction, User, Merchant, Health)
│   │   ├── dto/                    # Data Transfer Objects & API Response Wrappers
│   │   ├── model/
│   │   │   ├── entity/             # JPA Entities (User, Wallet, Transaction, Merchant)
│   │   │   └── enums/              # Enums (Role, TransactionType, Status)
│   │   ├── repository/             # Spring Data JPA Repositories
│   │   ├── service/                # Business Logic Services
│   │   ├── DataLoader.java         # Seed Data khởi tạo mẫu (Sinh viên, Ví, Cửa hàng)
│   │   └── CampusCoinApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml         # Cấu hình chung & Swagger
│   │   ├── application-dev.yml     # Profile H2 In-Memory DB
│   │   └── application-prod.yml    # Profile PostgreSQL DB
│   ├── pom.xml                     # Maven Dependencies
│   └── mvnw                        # Maven Wrapper
│
├── frontend/                       # Angular Frontend Application
│   ├── src/
│   │   ├── app/
│   │   │   ├── models/             # TypeScript Interfaces & DTOs
│   │   │   ├── services/           # CampusCoinService (API Client)
│   │   │   ├── app.ts              # Root Standalone Component Logic
│   │   │   ├── app.html            # Dashboard & UI Giao dịch, Cửa hàng
│   │   │   └── app.scss            # Styling hiện đại, responsive
│   │   ├── index.html
│   │   └── styles.scss             # Design tokens & Global theme
│   ├── proxy.conf.json             # Proxy API sang http://localhost:8080
│   ├── package.json
│   └── angular.json
│
├── .vscode/                        # Cấu hình VS Code
│   ├── launch.json                 # Cấu hình F5 Debug Full-Stack
│   ├── tasks.json                  # Tasks chạy dự án
│   ├── settings.json               # Cấu hình Java 21 & Angular
│   └── extensions.json             # Extension đề xuất
├── campus-coin.code-workspace       # VS Code Multi-root Workspace
├── docker-compose.yml              # PostgreSQL 16 & pgAdmin4
├── start-dev.sh                    # Script khởi chạy 1-click cả 2 service
└── README.md
```

---

## 🚀 Hướng Dẫn Khởi Chạy Dự Án

### Cách 1: Khởi chạy nhanh bằng script `start-dev.sh` (Khuyên dùng)
Tại thư mục gốc `campus-coin/`:
```bash
./start-dev.sh
```
Script sẽ tự động khởi động đồng thời cả Backend (port 8080) và Frontend (port 4200).

---

### Cách 2: Khởi chạy thủ công từng phần

#### 1. Khởi động Backend:
```bash
cd backend
./mvnw spring-boot:run
```
- API Base URL: `http://localhost:8080`
- **Swagger UI (Tài liệu API tương tác)**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **H2 Database Console**: [http://localhost:8080/h2-console](http://localhost:8080/h2-console)
  - JDBC URL: `jdbc:h2:mem:campuscoindb`
  - User: `sa`
  - Password: *(để trống)*

#### 2. Khởi động Frontend:
```bash
cd frontend
npm start
```
- Ứng dụng Web: [http://localhost:4200](http://localhost:4200)

---

### Cách 3: Sử dụng VS Code Tasks & Debugger
- Nhấn `Ctrl+Shift+P` (hoặc `Cmd+Shift+P` trên Mac) ➔ gõ `Tasks: Run Task` ➔ chọn:
  - `Start Full Stack (Backend + Frontend)`
  - Hoặc `Start Backend (Spring Boot)` / `Start Frontend (Angular)`
- Hoặc nhấn `F5` ➔ chọn cấu hình debug: `Full Stack (Backend + Frontend)`

---

## 🗄️ Sử Dụng PostgreSQL (Tùy Chọn Môi Trường Prod)

Nếu muốn chạy cơ sở dữ liệu PostgreSQL thay cho H2 In-Memory:
1. Khởi động PostgreSQL qua Docker:
   ```bash
   docker compose up -d
   ```
2. Chạy Backend với profile `prod`:
   ```bash
   cd backend
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
   ```
3. Truy cập pgAdmin tại [http://localhost:5050](http://localhost:5050)
   - Email: `admin@campuscoin.edu.vn`
   - Mật khẩu: `admin_password`

---

## 👥 Dữ Liệu Khởi Tạo Mẫu (Seed Data)

Khi khởi động, hệ thống tự động nạp sẵn dữ liệu mẫu để bạn thử nghiệm ngay lập tức trên giao diện web:

### Tài khoản sinh viên & giảng viên:
- **SV001** - `Nguyen Van An` (Khoa CNTT) - Số dư: **250.00 CCOIN**
- **SV002** - `Tran Thi Mai` (Khoa Kinh tế) - Số dư: **180.00 CCOIN**
- **SV003** - `Le Hoang Long` (Khoa ĐTVT) - Số dư: **320.00 CCOIN**
- **GV001** - `TS. Pham Quoc Hung` (Giảng viên CNTT) - Số dư: **500.00 CCOIN**

### Các điểm chấp nhận thanh toán (Merchants):
- `CANTEEN_A`: Canteen Khu A - Món ngon sinh viên
- `CAFE_CAMPUS`: Campus Coffee & Bakery
- `BOOKSTORE`: Hiệu sách & Văn phòng phẩm Đại học
- `PRINT_LIBRARY`: Trung tâm Thư viện & In ấn Photo

---

## 🐿️ AI Tools, Mascot & 3D Assets Attribution

- **Squirrel Mascot Animation**: Sourced vector motion asset with multi-state state machine (Idle, Walk, Deposit, Coin-Flip, Analyzing, Sleepy, Guest Roaming, Chat-Open) and interactive speech bubble, integrated via `lottie-web` under the **Lottie Simple License** (LottieFiles Community).
- **3D Falling Gold Coins Physics**: Interactive WebGL PBR rendering powered by `three` (Three.js r186, MIT License) and WebAssembly rigid-body physics via `@dimforge/rapier3d-compat` (Rapier 3D, Apache-2.0 License).
- **UI Iconography**: Standard functional UI icons powered by `lucide-angular` (ISC License) with custom bespoke vector squirrel brand identity marks (`squirrel-logo`, `favicon.svg`).
- **Production Performance**: Lazy-loaded heavy modules (`@defer (on idle)`), OnPush change detection, tree-shaking, and WebP asset optimization.

