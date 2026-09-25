# Campus Coin — Smart Spending, Student Style 🪙

> **Student-First Budgeting & Expense-Tracking Web Application**  
> Built with **Angular (v17+ Standalone Components)**, **Neubrutalism Design System**, and **Tailwind CSS**.

---

## 🚀 How to Run the Project

### Prerequisites
- Node.js (v18.x or v20.x recommended)
- npm (v9.x or v10.x)
- Angular CLI (`npm install -g @angular/cli`)

### Installation & Launch
```bash
# 1. Navigate to the frontend directory
cd frontend

# 2. Install dependencies
npm install

# 3. Start development server
npm start
# or: ng serve

# 4. Open in browser
# Navigate to: http://localhost:4200
```

---

## 🔑 Demo Accounts for Judges & Reviewers

Both student and administrative portals include **1-click auto-fill buttons** on their login screens for rapid evaluation:

| Role | Route | Email | Password | Features Accessible |
| :--- | :--- | :--- | :--- | :--- |
| **Student** | `/auth/login` | `alex.morgan@campus.edu` | `password123` | Feed, Conversational Quick Add, Analytics, Budgets, Insights, Categories, Profile & CSV Import |
| **Admin** | `/auth/admin-login` | `admin@campuscoin.edu` | `adminpass` | Campus Overview KPIs, User Directory (Disable/Reset), Default Categories & Advisory Broadcaster |

A complete nested list of all routes is available at **`/sitemap`**.

---

## 🎨 Design System: Neubrutalism

Campus Coin employs a distinctive **Neubrutalism** visual style tailored for modern university students:
1. **Bold High-Contrast Borders**: Thick `2.5px` and `3px` solid black borders (`#000000` in light mode, `#FFFFFF` in dark mode).
2. **Hard Offset Drop-Shadows**: Zero-blur box-shadows (`box-shadow: 4px 4px 0px 0px #000000`) that translate smoothly on hover (`-2px, -2px`) and depress on click (`+2px, +2px`).
3. **Punchy Student Palette**:
   - **Primary Accent**: Electric Student Yellow (`#FFE600`)
   - **Secondary Accent**: Mint Green (`#00F5A0`)
   - **Tertiary Accent**: Neon Coral / Pink (`#FF6B6B` / `#FF5C8D`)
   - **Base**: Warm Off-White (`#FFFDF5`) in light mode; Slate Dark (`#121214`) in dark mode.
4. **Typography**: Geometric headings using **Space Grotesk** paired with **Inter** for readable transaction rows and financial tables.
5. **Accessibility**: Integrated user font-size preference scaler (`small` 14px, `medium` 16px, `large` 18px) and accessible contrast ratios.

---

## 📱 Responsive Navigation Architecture

- **Mobile & Tablet (< 1024px)**: Fixed **Bottom Navigation Bar** with direct access to Feed, Reports, Prominent Center "+ Quick Add" button, Budgets, and Profile.
- **Desktop (≥ 1024px)**: Seamlessly transitions into a fixed **Left Sidebar** with expanded navigation items (AI Insights, Categories, Route Sitemap) and persistent user metadata.
- **Reactive Switching**: Driven by Angular CDK's `BreakpointObserver` with window media-query fallback, swapping layouts dynamically without duplicated DOM rendering.
- **Admin Portal**: Uses a sidebar-only, data-table-first calm dashboard layout at all viewports.

---

## 🧠 Where the "AI" Logic Lives (Guide for Judges)

For the student competition, all AI and NLP capabilities are architected as modular client services designed for easy backend LLM plug-in:

### 1. Conversational NLP Categorizer:
- **Location**: `src/app/core/services/ai-categorization.service.ts`
- **What it does**: Parses free-form text input such as `"Lunch at campus cafe 35k"` or `"Uber ride to station $8.50"`.
- **How it works**: Uses regex amount extraction and a keyword classification lexicon that maps student vernacular (e.g., dining, coffee, dorm, textbooks, tutoring) to category IDs with confidence scores (e.g. `94%`).
- **Production Extension**: In production, this service can easily point to an Anthropic Claude 3.5 Haiku or OpenAI GPT-4o-mini endpoint with structured JSON output.

### 2. Narrative Monthly Insights Engine:
- **Location**: `src/app/core/services/insight.service.ts` & `src/app/mock-data/insights.mock.ts`
- **What it does**: Generates monthly narrative financial summaries and student-specific saving hacks (e.g., highlighting coffee spend spikes and suggesting campus library refills).
- **Persistence**: Bookmarked tips are saved locally in `localStorage` across user sessions.

---

## 📂 Project Structure

```
frontend/src/
├── app/
│   ├── app.component.ts                 # Root component hosting router-outlet & theme init
│   ├── app.routes.ts                    # Lazy-loaded feature routing & guards
│   ├── app.config.ts                    # Application providers (Router, HttpClient)
│   │
│   ├── core/                            # Singletons, domain models, services & guards
│   │   ├── services/
│   │   │   ├── auth.service.ts          # Session management, student/admin login
│   │   │   ├── transaction.service.ts   # Transaction CRUD, balances, trend aggregation
│   │   │   ├── category.service.ts      # Default & custom category store
│   │   │   ├── budget.service.ts        # Category monthly limits & real-time alerts
│   │   │   ├── insight.service.ts       # Monthly AI narrative insights & bookmarking
│   │   │   ├── ai-categorization.service.ts # Conversational NLP parsing engine
│   │   │   ├── theme.service.ts         # Dark mode & font size scaler
│   │   │   └── admin.service.ts         # Institutional metrics & advisor templates
│   │   ├── guards/
│   │   │   ├── auth.guard.ts            # Student portal route protection
│   │   │   └── admin.guard.ts           # Admin portal route protection
│   │   ├── interceptors/
│   │   │   └── mock-delay.interceptor.ts# Simulated network latency (250ms)
│   │   └── models/
│   │       ├── user.model.ts
│   │       ├── transaction.model.ts
│   │       ├── category.model.ts
│   │       ├── budget.model.ts
│   │       └── insight.model.ts
│   │
│   ├── shared/                          # Reusable presentation components
│   │   ├── components/
│   │   │   ├── icon/                    # Crisp SVG Lucide icon renderer
│   │   │   ├── button/                  # Neubrutalist button variants
│   │   │   ├── card/                    # Neubrutalist card container
│   │   │   ├── progress-ring/           # Circular & bar progress indicators
│   │   │   ├── empty-state/             # Sourced image empty states with CTAs
│   │   │   ├── breadcrumbs/             # Sub-page navigation breadcrumbs
│   │   │   ├── loading-skeleton/        # Shimmer loading skeletons
│   │   │   ├── top-bar/                 # Sticky greeting & balance header
│   │   │   ├── nav-bottom/              # Fixed mobile navigation bar
│   │   │   └── nav-sidebar/             # Desktop sidebar navigation
│   │   └── navigation.config.ts         # Shared navigation schema
│   │
│   ├── layouts/
│   │   ├── student-layout/              # Responsive student portal wrapper
│   │   ├── admin-layout/                # Classic dashboard administrative wrapper
│   │   └── auth-layout/                 # Centered card layout for authentication
│   │
│   ├── features/
│   │   ├── auth/                        # Login, Register, Forgot Password, Admin Login
│   │   ├── home-feed/                   # Balance hero, AI card, budget meters, timeline
│   │   ├── quick-add/                   # Conversational AI input + manual form + soft-delete
│   │   ├── reports/                     # 6-Month trends, category donut, daily rhythm, PDF export
│   │   ├── budgets/                     # Limits manager, real-time pace & alert banners
│   │   ├── insights/                    # Narrative summary history & tip pinboard
│   │   ├── categories/                  # Category taxonomy management (Defaults + Custom)
│   │   ├── profile/                     # Profile info, theme toggle, font scaler, CSV import
│   │   ├── sitemap/                     # Complete visible route index
│   │   └── admin/                       # Institutional KPIs, user directory, advisor templates
│   │
│   └── mock-data/                       # Seed dataset (35+ multi-month transactions)
│       ├── users.mock.ts
│       ├── categories.mock.ts
│       ├── transactions.mock.ts
│       ├── budgets.mock.ts
│       └── insights.mock.ts
│
├── styles/
│   ├── styles.scss                      # Global styles, Tailwind directives, print CSS
│   └── _tokens.scss                     # Design system tokens (colors, shadows, radii)
│
├── environments/
│   ├── environment.ts                   # Production environment configuration
│   └── environment.development.ts       # Development environment configuration
│
└── tailwind.config.js                   # Neubrutalism color & shadow extensions
```

---

## 📸 Image Sourcing Compliance

In compliance with the project specifications, **no AI-generated images** are used. All imagery consists of real, freely-licensed photography from Unsplash with appropriate semantic student context:
- Student Avatars: Unsplash portrait photography (freely-licensed).
- AI Financial Advisor Avatar: Professional academic portrait (`photo-1573496359142-b8d87734a5a2`).
- Empty State Visuals: Student desk, ledger and calculator photography (`photo-1554224155-8d04cb21cd6c`).

---

## 🐿️ AI Tools, Mascot & 3D Assets Attribution

- **Squirrel Mascot Animation**: Sourced vector motion asset with multi-state state machine (Idle, Walk, Deposit, Coin-Flip, Analyzing, Sleepy, Guest Roaming, Chat-Open) and interactive speech bubble, integrated via `lottie-web` under the **Lottie Simple License** (LottieFiles Community).
- **3D Falling Gold Coins Physics**: Interactive WebGL PBR rendering powered by `three` (Three.js r186, MIT License) and WebAssembly rigid-body physics via `@dimforge/rapier3d-compat` (Rapier 3D, Apache-2.0 License).
- **UI Iconography**: Standard functional UI icons powered by `lucide-angular` (ISC License) with custom bespoke vector squirrel brand identity marks (`squirrel-logo`, `favicon.svg`).
- **Production Performance**: Lazy-loaded heavy modules (`@defer (on idle)`), OnPush change detection, tree-shaking, and WebP asset optimization.
