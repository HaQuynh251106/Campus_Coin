package com.campuscoin.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiContractIT extends AbstractMySqlIntegrationTest {

    private static final Set<String> DOCUMENTED_ENDPOINTS = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/verify",
            "POST /api/v1/auth/password-reset/complete",
            "POST /api/v1/admin/auth/login",
            "GET /api/v1/profile/me",
            "PATCH /api/v1/profile/me",
            "PATCH /api/v1/profile/me/preferences",
            "GET /api/v1/categories",
            "GET /api/v1/categories/{id}",
            "POST /api/v1/categories",
            "PATCH /api/v1/categories/{id}",
            "DELETE /api/v1/categories/{id}",
            "GET /api/v1/transactions",
            "GET /api/v1/transactions/{id}",
            "POST /api/v1/transactions",
            "PATCH /api/v1/transactions/{id}",
            "DELETE /api/v1/transactions/{id}",
            "POST /api/v1/transactions/{id}/restore",
            "GET /api/v1/recurring-rules",
            "GET /api/v1/recurring-rules/{id}",
            "POST /api/v1/recurring-rules",
            "PATCH /api/v1/recurring-rules/{id}",
            "DELETE /api/v1/recurring-rules/{id}",
            "GET /api/v1/budgets",
            "GET /api/v1/budgets/{id}",
            "POST /api/v1/budgets",
            "PATCH /api/v1/budgets/{id}",
            "DELETE /api/v1/budgets/{id}",
            "GET /api/v1/notifications",
            "GET /api/v1/notifications/{id}",
            "POST /api/v1/notifications/{id}/read",
            "GET /api/v1/dashboard",
            "GET /api/v1/reports",
            "GET /api/v1/reports/spending",
            "GET /api/v1/tips",
            "GET /api/v1/tips/months",
            "POST /api/v1/tips/generate",
            "POST /api/v1/tips/{id}/state",
            "GET /api/v1/bookmarks",
            "POST /api/v1/bookmarks",
            "PATCH /api/v1/bookmarks/{id}",
            "DELETE /api/v1/bookmarks/{id}",
            "GET /api/v1/admin/users",
            "POST /api/v1/admin/users/{id}/status",
            "POST /api/v1/admin/users/{id}/password-reset",
            "GET /api/v1/admin/categories",
            "POST /api/v1/admin/categories",
            "PATCH /api/v1/admin/categories/{id}",
            "GET /api/v1/admin/announcements",
            "POST /api/v1/admin/announcements",
            "PATCH /api/v1/admin/announcements/{id}",
            "GET /api/v1/admin/tip-templates",
            "POST /api/v1/admin/tip-templates",
            "PATCH /api/v1/admin/tip-templates/{id}",
            "GET /api/v1/admin/settings",
            "PATCH /api/v1/admin/settings/{key}",
            "GET /api/v1/admin/stats",
            "GET /api/v1/admin/stats/top-categories",
            "GET /api/v1/recent-activity",
            "POST /api/v1/recent-activity",
            "GET /api/v1/anomalies",
            "POST /api/v1/anomalies/scan",
            "GET /api/v1/forecast",
            "POST /api/v1/ai/suggest-category",
            "GET /api/v1/insights",
            "GET /api/v1/insights/months",
            "POST /api/v1/insights/generate",
            "POST /api/v1/imports",
            "GET /api/v1/imports",
            "GET /api/v1/imports/{batchId}",
            "PATCH /api/v1/imports/{batchId}/rows/{rowId}",
            "POST /api/v1/imports/{batchId}/commit",
            "POST /api/v1/imports/{batchId}/cancel",
            "GET /api/v1/chat",
            "POST /api/v1/chat");

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/password-reset/request",
            "POST /api/v1/auth/password-reset/verify",
            "POST /api/v1/auth/password-reset/complete",
            "POST /api/v1/admin/auth/login");

    private static final Set<String> FORBIDDEN_SCHEMA_FIELDS = Set.of(
            "passwordHash", "password_hash", "tokenVersion", "token_version",
            "refreshToken", "refresh_token", "resetToken", "reset_token",
            "password", "sessionToken", "session_token");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Section 13 phase 8: the generated document lists exactly the documented endpoints")
    void documentMatchesTheInventory() throws Exception {
        JsonNode document = openApiDocument();

        Set<String> actual = new TreeSet<>();
        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fieldNames().forEachRemaining(method -> {
                    if (isOperation(method)) {
                        actual.add(method.toUpperCase() + " " + path.getKey());
                    }
                }));

        assertThat(actual).isEqualTo(new TreeSet<>(DOCUMENTED_ENDPOINTS));
    }

    @Test
    @DisplayName("Section 13 phase 8: no path and method pair is registered twice")
    void noEndpointIsDuplicated() throws Exception {
        JsonNode document = openApiDocument();

        Set<String> paths = new TreeSet<>();
        document.get("paths").fieldNames().forEachRemaining(paths::add);

        assertThat(paths).doesNotContain("/api/v1/users/me", "/api/v1/profile", "/api/v1/user/me");

        assertThat(paths).doesNotContain("/api/v1/profile/me/categories",
                "/api/v1/categories/all", "/api/v1/categories/list");

        assertThat(paths).doesNotContain("/api/v1/transactions/all", "/api/v1/transactions/list",
                "/api/v1/transactions/deleted", "/api/v1/profile/me/transactions");

        assertThat(paths).doesNotContain("/api/v1/recurring-rules/all", "/api/v1/recurring-rules/list",
                "/api/v1/recurring", "/api/v1/profile/me/recurring-rules",
                "/api/v1/recurring-rules/{id}/pause", "/api/v1/recurring-rules/{id}/resume",
                "/api/v1/recurring-rules/{id}/run", "/api/v1/recurring-rules/{id}/occurrences");

        assertThat(paths).doesNotContain("/api/v1/budgets/all", "/api/v1/budgets/list",
                "/api/v1/profile/me/budgets", "/api/v1/budgets/{id}/spend",
                "/api/v1/budgets/{id}/status", "/api/v1/budgets/{id}/check",
                "/api/v1/budgets/recalculate", "/api/v1/budgets/alerts");

        assertThat(paths).doesNotContain("/api/v1/notifications/all", "/api/v1/notifications/list",
                "/api/v1/notifications/unread", "/api/v1/profile/me/notifications",
                "/api/v1/notifications/{id}/unread", "/api/v1/notifications/read-all");

        assertThat(paths).doesNotContain("/api/v1/reports/export", "/api/v1/reports/csv",
                "/api/v1/reports/download", "/api/v1/reports/summary", "/api/v1/reports/monthly",
                "/api/v1/reports/categories", "/api/v1/reports/all", "/api/v1/reports/list",
                "/api/v1/reports/{id}", "/api/v1/profile/me/reports");

        assertThat(paths).doesNotContain("/api/v1/tips/all", "/api/v1/tips/list",
                "/api/v1/tips/my-tips", "/api/v1/tips/for-me", "/api/v1/profile/me/tips",
                "/api/v1/tips/{id}", "/api/v1/tips/{id}/pin", "/api/v1/tips/{id}/unpin",
                "/api/v1/tips/{id}/dismiss", "/api/v1/tips/export", "/api/v1/tips/history",
                "/api/v1/tips/current");

        assertThat(paths).doesNotContain("/api/v1/bookmarks/all", "/api/v1/bookmarks/list",
                "/api/v1/bookmarks/saved", "/api/v1/bookmarks/my-bookmarks",
                "/api/v1/profile/me/bookmarks", "/api/v1/bookmarks/{id}/note",
                "/api/v1/bookmarks/{id}/pin", "/api/v1/bookmarks/{id}/unpin",
                "/api/v1/bookmarks/{id}/insights");

        assertThat(paths).doesNotContain("/api/v1/recent-activity/all", "/api/v1/recent-activity/list",
                "/api/v1/recent-activity/history", "/api/v1/recent-activity/clear",
                "/api/v1/recent-activity/reset", "/api/v1/profile/me/recent-activity",
                "/api/v1/recent", "/api/v1/recent-activities", "/api/v1/activities",
                "/api/v1/activity-log");

        assertThat(paths).doesNotContain("/api/v1/admin/users/{id}",
                "/api/v1/admin/users/{id}/disable", "/api/v1/admin/users/{id}/enable",
                "/api/v1/admin/users/{id}/reset-password", "/api/v1/admin/users/{id}/password",
                "/api/v1/admin/users/{id}/role", "/api/v1/admin/audit-log",
                "/api/v1/admin/audit", "/api/v1/admin/logs",
                "/api/v1/admin/categories/{id}/retire", "/api/v1/admin/categories/{id}/activate",
                "/api/v1/admin/categories/defaults",
                "/api/v1/admin/announcements/{id}/activate",
                "/api/v1/admin/announcements/{id}/deactivate",
                "/api/v1/admin/announcements/{id}/publish",
                "/api/v1/admin/tip-templates/{id}/activate",
                "/api/v1/admin/tip-templates/{id}/deactivate",
                "/api/v1/admin/settings/all", "/api/v1/admin/settings/list",
                "/api/v1/admin/thresholds",
                "/api/v1/admin/stats/categories", "/api/v1/admin/stats/usage",
                "/api/v1/admin/insights", "/api/v1/admin/anomalies", "/api/v1/admin/ai");

        assertThat(paths).doesNotContain("/api/v1/anomalies/{id}",
                "/api/v1/anomalies/all", "/api/v1/anomalies/list", "/api/v1/anomalies/flagged",
                "/api/v1/anomalies/clear", "/api/v1/anomalies/reset",
                "/api/v1/anomalies/detect", "/api/v1/anomalies/check", "/api/v1/anomalies/rescan",
                "/api/v1/transactions/anomalies", "/api/v1/transactions/flagged",
                "/api/v1/flagged-transactions", "/api/v1/anomaly-flags");

        assertThat(paths).doesNotContain("/api/v1/forecast/{month}",
                "/api/v1/forecast/history", "/api/v1/forecast/months", "/api/v1/forecast/next",
                "/api/v1/forecast/projection", "/api/v1/forecast/summary",
                "/api/v1/predict", "/api/v1/projection", "/api/v1/predictions",
                "/api/v1/profile/me/forecast");

        assertThat(paths).doesNotContain("/api/v1/transactions/{id}/categorise",
                "/api/v1/transactions/{id}/categorize", "/api/v1/transactions/{id}/suggest",
                "/api/v1/transactions/suggest-category", "/api/v1/transactions/categorise",
                "/api/v1/categories/{id}/suggest", "/api/v1/ai", "/api/v1/ai/suggest",
                "/api/v1/ai/categorise", "/api/v1/ai/insights", "/api/v1/ai/anomalies",
                "/api/v1/ai/forecast", "/api/v1/ai/status");

        assertThat(paths).doesNotContain("/api/v1/insights/{month}",
                "/api/v1/insights/{id}", "/api/v1/insights/2026-09", "/api/v1/insights/latest",
                "/api/v1/insights/current", "/api/v1/insights/summary", "/api/v1/insights/refresh",
                "/api/v1/insights/regenerate", "/api/v1/insights/{month}/regenerate",
                "/api/v1/insights/generate/{month}", "/api/v1/profile/me/insights",
                "/api/v1/monthly-insights", "/api/v1/insight");

        assertThat(paths).doesNotContain("/api/v1/imports/upload", "/api/v1/imports/csv",
                "/api/v1/imports/preview", "/api/v1/imports/{batchId}/preview",
                "/api/v1/imports/{batchId}/rows", "/api/v1/imports/{batchId}/rows/{rowId}/category",
                "/api/v1/imports/{batchId}/status", "/api/v1/imports/{batchId}/reopen",
                "/api/v1/imports/{batchId}/errors", "/api/v1/imports/{batchId}/invalid",
                "/api/v1/imports/{batchId}/duplicates", "/api/v1/imports/{batchId}/rollback",
                "/api/v1/imports/{id}", "/api/v1/import/{batchId}", "/api/v1/csv-imports",
                "/api/v1/import-batches");

        assertThat(paths).hasSize(57);
    }

    @Test
    @DisplayName("Section 7.5: the document marks public endpoints public and the rest bearer-guarded")
    void securityRequirementsMatchThePolicy() throws Exception {
        JsonNode document = openApiDocument();

        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(operation -> {
                    if (!isOperation(operation.getKey())) {
                        return;
                    }
                    String key = operation.getKey().toUpperCase() + " " + path.getKey();
                    JsonNode security = operation.getValue().get("security");

                    if (PUBLIC_ENDPOINTS.contains(key)) {

                        boolean declaredPublic = security == null || security.isEmpty();
                        assertThat(declaredPublic)
                                .as("%s must be documented as public", key)
                                .isTrue();
                    } else {

                        assertThat(security).as("%s must require a token", key).isNotNull();
                        assertThat(security.toString()).as("%s", key).contains("bearerAuth");
                    }
                }));

        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText())
                .isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText())
                .isEqualTo("http");
    }

    @Test
    @DisplayName("Section 7.2: no response schema exposes a password, hash or internal token")
    void noResponseSchemaExposesSensitiveFields() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");
        assertThat(schemas.isObject()).isTrue();

        Set<String> responseSchemas = new TreeSet<>();
        document.get("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(operation -> {
                    if (!isOperation(operation.getKey())) {
                        return;
                    }
                    JsonNode responses = operation.getValue().get("responses");
                    if (responses == null) {
                        return;
                    }
                    responses.fields().forEachRemaining(response ->
                            collectSchemaReferences(response.getValue(), responseSchemas));
                }));

        assertThat(responseSchemas).contains("ProfileResponse", "AuthResponse", "ApiError",
                "CategoryResponse", "TransactionResponse", "RecurringRuleResponse",
                "BudgetResponse", "NotificationResponse", "DashboardResponse", "ReportResponse",
                "SpendingSeriesResponse", "TipListResponse", "TipResponse", "TipMonthsResponse",
                "BookmarkResponse", "AdminUserResponse", "AnnouncementResponse",
                "TipTemplateResponse", "SystemSettingResponse", "AdminUsageStatsResponse",
                "AdminTopCategoryResponse", "AdminPasswordResetResponse",
                "RecentActivityResponse", "RecentActivityListResponse",
                "FlaggedTransactionListResponse", "AnomalyScanResponse", "ForecastResponse",
                "CategorySuggestionResponse", "MonthlyInsightResponse", "InsightMonthsResponse",
                "ImportBatchResponse", "ImportBatchListResponse", "ImportRowResponse");

        for (String name : responseSchemas) {
            JsonNode properties = schemas.at("/" + name + "/properties");
            if (!properties.isObject()) {
                continue;
            }
            properties.fieldNames().forEachRemaining(field ->
                    assertThat(FORBIDDEN_SCHEMA_FIELDS)
                            .as("response schema %s must not expose %s", name, field)
                            .doesNotContain(field));
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the profile contract has the documented shape")
    void profileSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "ProfileResponse")).containsExactlyInAnyOrder(
                "id", "fullName", "email", "academicYear", "monthlyAllowanceBaseline",
                "monthlySavingsGoal", "currency", "themePreference", "fontScale");
        assertThat(fieldNames(schemas, "UpdateProfileRequest")).containsExactlyInAnyOrder(
                "fullName", "academicYear", "monthlyAllowanceBaseline", "monthlySavingsGoal");
        assertThat(fieldNames(schemas, "UpdatePreferencesRequest"))
                .containsExactlyInAnyOrder("themePreference", "fontScale");

        assertThat(enumValues(schemas, "ProfileResponse", "themePreference"))
                .containsExactlyInAnyOrder("LIGHT", "DARK", "SYSTEM");
        assertThat(enumValues(schemas, "ProfileResponse", "fontScale"))
                .containsExactlyInAnyOrder("SMALL", "MEDIUM", "LARGE", "XLARGE");

        assertThat(enumValues(schemas, "UpdatePreferencesRequest", "themePreference"))
                .containsExactlyInAnyOrder("LIGHT", "DARK", "SYSTEM");
        assertThat(enumValues(schemas, "UpdatePreferencesRequest", "fontScale"))
                .containsExactlyInAnyOrder("SMALL", "MEDIUM", "LARGE", "XLARGE");

        assertThat(fieldNames(schemas, "FieldError")).containsExactlyInAnyOrder("field", "message");
        assertThat(fieldNames(schemas, "ApiError")).containsExactlyInAnyOrder(
                "timestamp", "status", "errorCode", "message", "path", "fieldErrors");
    }

    @Test
    @DisplayName("Section 13 phase 7: the category contract has the documented shape")
    void categorySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "CategoryResponse")).containsExactlyInAnyOrder(
                "id", "name", "type", "icon", "color", "isDefault", "isActive", "sortOrder",
                "description");
        assertThat(fieldNames(schemas, "CategoryResponse")).doesNotContain("userId", "user_id",
                "createdBy", "created_by");

        assertThat(fieldNames(schemas, "CreateCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");

        assertThat(fieldNames(schemas, "UpdateCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");
        assertThat(fieldNames(schemas, "UpdateCategoryRequest")).doesNotContain("isDefault",
                "userId", "createdBy");

        assertThat(enumValues(schemas, "CategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "CreateCategoryRequest", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "UpdateCategoryRequest", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
    }

    @Test
    @DisplayName("Section 13 phase 7: the transaction contract has the documented shape")
    void transactionSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "TransactionResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
                "amount", "txnDate", "description", "source", "isDeleted", "deletedAt");
        assertThat(fieldNames(schemas, "TransactionResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt");

        for (String request : new String[] {"CreateTransactionRequest", "UpdateTransactionRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own type, source or owner", request)
                    .doesNotContain("type", "source", "userId", "user_id", "isDeleted",
                            "deletedAt", "aiSuggestedCategoryId", "aiConfidence", "aiOverridden",
                            "isFlagged", "flagType", "flagNote", "recurringRuleId",
                            "importBatchId");
        }

        assertThat(fieldNames(schemas, "CreateTransactionRequest"))
                .containsExactlyInAnyOrder("categoryId", "amount", "txnDate", "description");
        assertThat(fieldNames(schemas, "UpdateTransactionRequest"))
                .containsExactlyInAnyOrder("categoryId", "amount", "txnDate", "description");

        assertThat(enumValues(schemas, "TransactionResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");

        assertThat(enumValues(schemas, "TransactionResponse", "source"))
                .containsExactlyInAnyOrder("MANUAL", "CSV", "RECURRING");
    }

    @Test
    @DisplayName("Section 13 phase 7: the recurring-rule contract has the documented shape")
    void recurringSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "RecurringRuleResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "type",
                "amount", "description", "frequency", "intervalCount", "startDate", "endDate",
                "nextRunDate", "lastRunDate", "status");
        assertThat(fieldNames(schemas, "RecurringRuleResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt",
                        "dayOfMonth", "dayOfWeek", "lastPostedAt");

        for (String request : new String[] {"CreateRecurringRuleRequest", "UpdateRecurringRuleRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own type or history", request)
                    .doesNotContain("type", "userId", "user_id", "lastRunDate", "last_run_date",
                            "dayOfMonth", "dayOfWeek", "createdAt", "updatedAt");
        }
        assertThat(fieldNames(schemas, "UpdateRecurringRuleRequest")).doesNotContain("startDate",
                "start_date");

        assertThat(fieldNames(schemas, "UpdateRecurringRuleRequest")).containsExactlyInAnyOrder(
                "categoryId", "amount", "description", "frequency", "intervalCount", "endDate",
                "nextRunDate", "status");
        assertThat(fieldNames(schemas, "CreateRecurringRuleRequest")).containsExactlyInAnyOrder(
                "categoryId", "amount", "description", "frequency", "intervalCount", "startDate",
                "endDate", "nextRunDate");

        assertThat(enumValues(schemas, "RecurringRuleResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");
        assertThat(enumValues(schemas, "RecurringRuleResponse", "frequency"))
                .containsExactlyInAnyOrder("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY");
        assertThat(enumValues(schemas, "RecurringRuleResponse", "status"))
                .containsExactlyInAnyOrder("ACTIVE", "PAUSED", "ENDED");
        assertThat(enumValues(schemas, "CreateRecurringRuleRequest", "frequency"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "frequency"));
        assertThat(enumValues(schemas, "UpdateRecurringRuleRequest", "frequency"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "frequency"));
        assertThat(enumValues(schemas, "UpdateRecurringRuleRequest", "status"))
                .isEqualTo(enumValues(schemas, "RecurringRuleResponse", "status"));

        assertThat(schemas.at("/CreateRecurringRuleRequest/properties/endDate/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/UpdateRecurringRuleRequest/properties/endDate/type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("Section 13 phase 7: the budget contract has the documented shape")
    void budgetSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "BudgetResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "categoryName", "categoryIcon", "categoryColor", "periodMonth",
                "limitAmount", "spentAmount", "remainingAmount", "consumedPct",
                "consumptionStatus");
        assertThat(fieldNames(schemas, "BudgetResponse"))
                .doesNotContain("userId", "user_id", "createdAt", "updatedAt",
                        "type", "categoryType", "isActive");

        for (String request : new String[] {"CreateBudgetRequest", "UpdateBudgetRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose its own owner, spend or status", request)
                    .doesNotContain("id", "userId", "user_id", "spentAmount", "spent_amount",
                            "remainingAmount", "consumedPct", "consumptionStatus", "type",
                            "isDeleted", "createdAt", "updatedAt");
        }

        assertThat(fieldNames(schemas, "UpdateBudgetRequest")).containsExactlyInAnyOrder(
                "limitAmount");
        assertThat(fieldNames(schemas, "CreateBudgetRequest")).containsExactlyInAnyOrder(
                "categoryId", "periodMonth", "limitAmount");

        assertThat(enumValues(schemas, "BudgetResponse", "consumptionStatus"))
                .containsExactlyInAnyOrder("ON_TRACK", "NEAR", "EXCEEDED");

        assertThat(schemas.at("/BudgetResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/CreateBudgetRequest/properties/periodMonth/type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("Section 13 phase 7: the notification contract has the documented shape")
    void notificationSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "NotificationResponse")).containsExactlyInAnyOrder(
                "id", "type", "title", "body", "linkUrl", "refEntityType", "refEntityId", "isRead",
                "readAt", "createdAt");
        assertThat(fieldNames(schemas, "NotificationResponse"))
                .doesNotContain("userId", "user_id", "updatedAt", "isDeleted", "deletedAt");

        for (String forbidden : new String[] {"CreateNotificationRequest",
                "UpdateNotificationRequest", "NotificationRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: nothing about a notification is the student's to author",
                            forbidden)
                    .isFalse();
        }

        assertThat(enumValues(schemas, "NotificationResponse", "type"))
                .containsExactlyInAnyOrder("BUDGET_NEAR", "BUDGET_EXCEEDED", "ANNOUNCEMENT",
                        "SYSTEM", "INSIGHT_READY", "TIP", "RECURRING_POSTED");

        assertThat(schemas.at("/NotificationResponse/properties/readAt").isMissingNode()).isFalse();
        assertThat(schemas.at("/NotificationResponse/properties/isRead/type").asText())
                .isEqualTo("boolean");
    }

    @Test
    @DisplayName("Section 13 phase 7: the report contract has the documented shape")
    void reportSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "ReportResponse")).containsExactlyInAnyOrder(
                "periodMonth", "currency", "totals", "expenseByCategory", "incomeByCategory",
                "sixMonthTrend");
        assertThat(fieldNames(schemas, "ReportResponse"))
                .doesNotContain("userId", "user_id", "month", "from", "to");

        assertThat(fieldNames(schemas, "ReportTotalsResponse")).containsExactlyInAnyOrder(
                "income", "expense", "net", "transactionCount");

        assertThat(fieldNames(schemas, "ReportCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "categoryIcon", "categoryColor", "type", "total",
                "percentage", "transactionCount");
        assertThat(fieldNames(schemas, "ReportCategoryResponse"))
                .doesNotContain("userId", "user_id", "categoryType");
        assertThat(enumValues(schemas, "ReportCategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");

        assertThat(fieldNames(schemas, "ReportTrendPointResponse")).containsExactlyInAnyOrder(
                "periodMonth", "income", "expense", "net");

        assertThat(fieldNames(schemas, "SpendingSeriesResponse")).containsExactlyInAnyOrder(
                "granularity", "from", "to", "currency", "totalExpense", "points");
        assertThat(fieldNames(schemas, "SpendingPointResponse")).containsExactlyInAnyOrder(
                "intervalStart", "intervalEnd", "totalExpense", "transactionCount");

        assertThat(fieldNames(schemas, "SpendingPointResponse"))
                .doesNotContain("userId", "user_id", "weekStart", "weekEnd");

        for (String forbidden : new String[] {"CreateReportRequest", "UpdateReportRequest",
                "ReportRequest", "ExportReportRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a report is read and never written", forbidden)
                    .isFalse();
        }

        assertThat(schemas.at("/ReportResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/ReportTrendPointResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");

        assertThat(enumValues(schemas, "SpendingSeriesResponse", "granularity"))
                .containsExactlyInAnyOrder("DAILY", "WEEKLY");
    }

    @Test
    @DisplayName("Section 13 phase 7: the saving-tip contract has the documented shape")
    void tipSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "TipResponse")).containsExactlyInAnyOrder(
                "id", "categoryId", "title", "body", "potentialSaving", "state");
        assertThat(fieldNames(schemas, "TipResponse"))
                .doesNotContain("userId", "user_id", "rankScore", "rank_score", "periodMonth",
                        "tipTemplateId", "tip_template_id", "dedupeKey", "dedupe_key",
                        "generatedAt", "pinnedAt", "dismissedAt", "createdAt", "updatedAt");

        assertThat(fieldNames(schemas, "TipListResponse"))
                .containsExactlyInAnyOrder("periodMonth", "tips");
        assertThat(fieldNames(schemas, "TipMonthsResponse")).containsExactlyInAnyOrder("months");

        assertThat(fieldNames(schemas, "UpdateTipStateRequest")).containsExactlyInAnyOrder("state");
        assertThat(fieldNames(schemas, "UpdateTipStateRequest"))
                .doesNotContain("userId", "id", "pinnedAt", "dismissedAt", "periodMonth",
                        "title", "body", "potentialSaving");

        for (String forbidden : new String[] {"CreateTipRequest", "UpdateTipRequest",
                "DeleteTipRequest", "TipRequest", "GenerateTipsRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a tip is generated by the database, never authored",
                            forbidden)
                    .isFalse();
        }

        assertThat(enumValues(schemas, "UpdateTipStateRequest", "state"))
                .containsExactlyInAnyOrder("NEW", "PINNED", "DISMISSED");
        assertThat(enumValues(schemas, "TipResponse", "state"))
                .isEqualTo(enumValues(schemas, "UpdateTipStateRequest", "state"));

        assertThat(schemas.at("/TipListResponse/properties/periodMonth/type").asText())
                .isEqualTo("string");
        assertThat(schemas.at("/TipMonthsResponse/properties/months/items/type").asText())
                .isEqualTo("string");

        assertThat(schemas.at("/TipResponse/properties/categoryId").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Section 13 phase 7: the bookmark contract has the documented shape")
    void bookmarkSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "BookmarkResponse")).containsExactlyInAnyOrder(
                "id", "itemType", "tipId", "tipTitle", "tipBody", "tipPotentialSaving",
                "tipState", "tipMonth", "note", "createdAt");
        assertThat(fieldNames(schemas, "BookmarkResponse"))
                .doesNotContain("userId", "user_id", "insightId", "insight_id", "createdBy",
                        "dedupeKey", "dedupe_key", "rankScore", "rank_score", "categoryId",
                        "tipCategoryId", "updatedAt", "deletedAt");

        assertThat(fieldNames(schemas, "CreateBookmarkRequest"))
                .containsExactlyInAnyOrder("itemType", "itemId", "note");
        assertThat(fieldNames(schemas, "CreateBookmarkRequest"))
                .doesNotContain("userId", "user_id", "tipId", "insightId", "createdAt",
                        "dedupeKey", "dedupe_key");

        assertThat(fieldNames(schemas, "UpdateBookmarkNoteRequest")).containsExactlyInAnyOrder("note");
        assertThat(fieldNames(schemas, "UpdateBookmarkNoteRequest"))
                .doesNotContain("itemType", "itemId", "tipId", "insightId", "userId", "id",
                        "createdAt");

        assertThat(enumValues(schemas, "CreateBookmarkRequest", "itemType"))
                .containsExactlyInAnyOrder("TIP", "INSIGHT");
        assertThat(enumValues(schemas, "BookmarkResponse", "itemType"))
                .isEqualTo(enumValues(schemas, "CreateBookmarkRequest", "itemType"));
        assertThat(enumValues(schemas, "BookmarkResponse", "tipState"))
                .containsExactlyInAnyOrder("NEW", "PINNED", "DISMISSED");

        for (String forbidden : new String[] {"UpdateBookmarkRequest", "CreateBookmarkNoteRequest",
                "BookmarkNoteRequest", "MoveBookmarkRequest", "CreateInsightRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: a bookmark's target is what it is", forbidden)
                    .isFalse();
        }

        assertThat(schemas.at("/BookmarkResponse/properties/note").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("Section 13 phase 7: the administration contract has the documented shape")
    void adminSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "AdminUserResponse")).containsExactlyInAnyOrder(
                "id", "email", "fullName", "role", "status", "academicYear", "lastLoginAt",
                "createdAt");
        assertThat(fieldNames(schemas, "AdminUserResponse")).doesNotContain("passwordHash",
                "password_hash", "password", "tokenVersion", "token_version", "updatedAt",
                "updated_at", "monthlyAllowanceBaseline", "monthly_allowance_baseline",
                "monthlySavingsGoal", "monthly_savings_goal", "themePreference", "theme_preference",
                "fontScale", "font_scale", "aiEnabled", "ai_enabled", "emailVerifiedAt",
                "email_verified_at");

        assertThat(enumValues(schemas, "AdminUserResponse", "role"))
                .containsExactlyInAnyOrder("STUDENT", "ADMIN");
        assertThat(enumValues(schemas, "AdminUserResponse", "status"))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");

        assertThat(fieldNames(schemas, "SetUserStatusRequest")).containsExactlyInAnyOrder("status");
        assertThat(fieldNames(schemas, "SetUserStatusRequest"))
                .doesNotContain("userId", "user_id", "id", "tokenVersion", "token_version");
        assertThat(enumValues(schemas, "SetUserStatusRequest", "status"))
                .isEqualTo(enumValues(schemas, "AdminUserResponse", "status"));

        assertThat(fieldNames(schemas, "AdminPasswordResetResponse"))
                .containsExactlyInAnyOrder("message");
        assertThat(fieldNames(schemas, "AdminPasswordResetResponse")).doesNotContain("token",
                "resetToken", "reset_token", "resetLink", "reset_link", "url", "expiresAt");

        assertThat(fieldNames(schemas, "CategoryResponse")).contains("isDefault");
        for (String request : new String[] {"UpsertDefaultCategoryRequest",
                "UpdateDefaultCategoryRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not let a client choose the row's scope or owner", request)
                    .doesNotContain("isDefault", "userId", "user_id", "createdBy", "created_by");
        }
        assertThat(fieldNames(schemas, "UpsertDefaultCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");
        assertThat(fieldNames(schemas, "UpdateDefaultCategoryRequest")).containsExactlyInAnyOrder(
                "name", "type", "icon", "color", "description", "sortOrder", "isActive");

        assertThat(fieldNames(schemas, "AnnouncementResponse")).containsExactlyInAnyOrder(
                "id", "title", "body", "severity", "audience", "startsAt", "endsAt", "isActive",
                "createdAt");
        assertThat(fieldNames(schemas, "AnnouncementResponse")).doesNotContain("createdBy",
                "created_by", "updatedAt", "updated_at", "userId", "user_id");

        assertThat(fieldNames(schemas, "CreateAnnouncementRequest")).containsExactlyInAnyOrder(
                "title", "body", "severity", "audience", "startsAt", "endsAt");
        assertThat(fieldNames(schemas, "CreateAnnouncementRequest")).doesNotContain("isActive",
                "createdBy", "id");

        assertThat(fieldNames(schemas, "UpdateAnnouncementRequest"))
                .containsExactlyInAnyOrder("isActive");
        assertThat(fieldNames(schemas, "UpdateAnnouncementRequest")).doesNotContain("title", "body",
                "severity", "audience", "startsAt", "endsAt", "createdBy");

        assertThat(enumValues(schemas, "AnnouncementResponse", "severity"))
                .containsExactlyInAnyOrder("INFO", "WARNING", "SUCCESS");
        assertThat(enumValues(schemas, "AnnouncementResponse", "audience"))
                .containsExactlyInAnyOrder("ALL", "STUDENTS", "ADMINS");
        assertThat(enumValues(schemas, "CreateAnnouncementRequest", "severity"))
                .isEqualTo(enumValues(schemas, "AnnouncementResponse", "severity"));
        assertThat(enumValues(schemas, "CreateAnnouncementRequest", "audience"))
                .isEqualTo(enumValues(schemas, "AnnouncementResponse", "audience"));

        assertThat(fieldNames(schemas, "TipTemplateResponse")).containsExactlyInAnyOrder(
                "id", "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        assertThat(fieldNames(schemas, "TipTemplateResponse")).doesNotContain("conditionParams",
                "condition_params", "createdBy", "created_by", "updatedAt", "updated_at");

        assertThat(fieldNames(schemas, "CreateTipTemplateRequest")).containsExactlyInAnyOrder(
                "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        assertThat(fieldNames(schemas, "UpdateTipTemplateRequest")).containsExactlyInAnyOrder(
                "code", "conditionType", "titleTemplate", "bodyTemplate", "defaultPriority",
                "isActive");
        for (String request : new String[] {"CreateTipTemplateRequest",
                "UpdateTipTemplateRequest"}) {
            assertThat(fieldNames(schemas, request))
                    .as("%s must not reach the column nothing in this build reads", request)
                    .doesNotContain("conditionParams", "condition_params", "createdBy",
                            "created_by");
        }
        assertThat(enumValues(schemas, "TipTemplateResponse", "conditionType"))
                .containsExactlyInAnyOrder("OVER_BUDGET", "NEAR_BUDGET", "CATEGORY_SPIKE",
                        "NO_BUDGET_SET", "SAVINGS_GOAL_AT_RISK", "LOW_SAVINGS_RATE", "GENERIC");

        assertThat(fieldNames(schemas, "SystemSettingResponse")).containsExactlyInAnyOrder(
                "key", "value", "valueType", "description", "adjustable");
        assertThat(fieldNames(schemas, "SystemSettingResponse")).doesNotContain("updatedBy",
                "updated_by", "updatedAt", "updated_at", "settingKey", "settingValue");

        assertThat(fieldNames(schemas, "UpdateThresholdRequest"))
                .containsExactlyInAnyOrder("value");
        assertThat(fieldNames(schemas, "UpdateThresholdRequest"))
                .doesNotContain("key", "settingKey", "valueType");

        assertThat(fieldNames(schemas, "AdminUsageStatsResponse")).containsExactlyInAnyOrder(
                "totalStudents", "activeStudents", "disabledStudents", "activeUsers30d",
                "totalTransactions", "totalExpenseLogged", "totalIncomeLogged", "totalBudgets",
                "totalTipsGenerated", "totalInsightsGenerated");
        assertThat(fieldNames(schemas, "AdminUsageStatsResponse"))
                .doesNotContain("userId", "user_id", "email", "fullName", "studentId",
                        "student_id");

        assertThat(fieldNames(schemas, "AdminTopCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "type", "scope", "txnCount", "totalAmount",
                "distinctUsers");
        assertThat(fieldNames(schemas, "AdminTopCategoryResponse"))
                .doesNotContain("userId", "user_id", "email", "fullName", "categoryIcon",
                        "categoryColor");
        assertThat(enumValues(schemas, "AdminTopCategoryResponse", "type"))
                .containsExactlyInAnyOrder("INCOME", "EXPENSE");

        for (String forbidden : new String[] {"CreateInsightRequest", "UpdateInsightRequest",
                "AdminInsightResponse", "AdminAnomalyResponse", "AdminAiResponse",
                "CreateAuditLogRequest", "AdminAuditResponse", "DeleteUserRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: it names a capability this module does not have",
                            forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the recent-activity contract has the documented shape")
    void recentActivitySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "RecentActivityResponse")).containsExactlyInAnyOrder(
                "transactionId", "action", "occurredAt", "categoryId", "categoryType", "amount",
                "description", "txnDate");
        assertThat(fieldNames(schemas, "RecentActivityResponse"))
                .doesNotContain("id", "userId", "user_id", "encryptedDescription",
                        "encrypted_description", "isDeleted", "is_deleted");

        assertThat(fieldNames(schemas, "RecentActivityListResponse"))
                .containsExactlyInAnyOrder("limit", "entries");
        assertThat(fieldNames(schemas, "RecentActivityListResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        assertThat(fieldNames(schemas, "RecordRecentActivityRequest"))
                .containsExactlyInAnyOrder("transactionId", "action");
        assertThat(fieldNames(schemas, "RecordRecentActivityRequest"))
                .doesNotContain("userId", "user_id", "id", "occurredAt", "occurred_at",
                        "categoryId", "amount");

        assertThat(enumValues(schemas, "RecordRecentActivityRequest", "action"))
                .containsExactlyInAnyOrder("VIEWED", "EDITED");
        assertThat(enumValues(schemas, "RecentActivityResponse", "action"))
                .isEqualTo(enumValues(schemas, "RecordRecentActivityRequest", "action"));

        assertThat(schemas.at("/RecentActivityResponse/properties/description").isMissingNode())
                .isFalse();

        for (String forbidden : new String[] {"ClearRecentActivityRequest", "DeleteRecentActivityRequest",
                "RecentActivityIdResponse", "UpdateRecentActivityRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-26 offers no such operation", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the anomaly contract has the documented shape")
    void anomalySchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "FlaggedTransactionResponse")).containsExactlyInAnyOrder(
                "transactionId", "categoryId", "categoryName", "categoryType", "amount", "txnDate",
                "description", "isFlagged", "flagType", "flagNote");
        assertThat(fieldNames(schemas, "FlaggedTransactionResponse"))
                .doesNotContain("id", "userId", "user_id", "encryptedDescription",
                        "encrypted_description", "isDeleted", "is_deleted", "categorySpend");

        assertThat(fieldNames(schemas, "FlaggedTransactionListResponse"))
                .containsExactlyInAnyOrder("limit", "entries");
        assertThat(fieldNames(schemas, "FlaggedTransactionListResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        assertThat(fieldNames(schemas, "AnomalyScanResponse")).containsExactlyInAnyOrder(
                "examined", "flagged", "cleared", "unchanged", "entries");
        assertThat(fieldNames(schemas, "AnomalyScanResponse"))
                .doesNotContain("userId", "user_id", "scannedAt", "durationMs");

        for (String forbidden : new String[] {"FlagTransactionRequest", "SetFlagRequest",
                "AnomalyScanRequest", "ClearAnomaliesRequest", "UpdateAnomalyRequest",
                "AnomalyIdResponse"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-24 gives a client no way to set its own marks", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the forecast contract has the documented shape")
    void forecastSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "ForecastResponse")).containsExactlyInAnyOrder(
                "nextMonth", "currentMonth", "basedOnMonths", "recentMonths", "currentMonthTotals",
                "projected");
        assertThat(fieldNames(schemas, "ForecastResponse"))
                .doesNotContain("userId", "user_id", "email", "month", "windowMonths",
                        "window_months", "generatedAt", "asOf");

        assertThat(fieldNames(schemas, "ForecastMonthResponse"))
                .containsExactlyInAnyOrder("periodMonth", "income", "expense", "net");

        assertThat(fieldNames(schemas, "CurrentMonthTotalsResponse"))
                .containsExactlyInAnyOrder("income", "expense", "net");

        assertThat(fieldNames(schemas, "ProjectedMonthResponse"))
                .containsExactlyInAnyOrder("income", "expense", "savings");

        for (String forbidden : new String[] {"ForecastRequest", "CreateForecastRequest",
                "UpdateForecastRequest", "ProjectionRequest", "ForecastMonthRequest",
                "SetForecastRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-25 accepts no input", forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the categorisation contract has the documented shape")
    void categorisationSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "CategorySuggestionResponse")).containsExactlyInAnyOrder(
                "transactionId", "source", "categoryId", "categoryName", "type", "confidence",
                "reason", "learned");
        assertThat(fieldNames(schemas, "CategorySuggestionResponse"))
                .doesNotContain("userId", "user_id", "email", "description", "categoryRules",
                        "candidates", "provider", "model", "overridden", "suggestedCategoryId");

        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse")).containsExactlyInAnyOrder(
                "keyword", "categoryId", "categoryName", "source");
        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse"))
                .doesNotContain("ruleId", "id", "userId", "user_id", "hitCount", "hit_count",
                        "confidence", "lastUsedAt", "last_used_at", "matchMode", "match_mode",
                        "createdAt", "updatedAt");

        assertThat(fieldNames(schemas, "LearnedCategoryRuleResponse"))
                .doesNotContain("password", "passwordHash", "password_hash", "resetToken",
                        "reset_token", "sessionToken", "session_token", "refreshToken",
                        "refresh_token", "tokenVersion", "token_version", "email");

        assertThat(enumValues(schemas, "CategorySuggestionResponse", "source"))
                .containsExactlyInAnyOrder("NONE", "RULE", "AI");

        assertThat(enumValues(schemas, "LearnedCategoryRuleResponse", "source"))
                .containsExactlyInAnyOrder("ACCEPTED", "OVERRIDE", "IMPORT");

        assertThat(fieldNames(schemas, "SuggestCategoryRequest"))
                .containsExactlyInAnyOrder("transactionId");

        for (String forbidden : new String[] {"CategoriseRequest", "CategorizeRequest",
                "CategorySuggestionRequest", "CreateCategoryRuleRequest", "CategoryRuleRequest",
                "UpdateCategoryRuleRequest", "SetSuggestedCategoryRequest", "CategoriseTransactionRequest"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-08 gives a client no way to state a conclusion",
                            forbidden)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Section 13 phase 7: the insight contract has the documented shape")
    void insightSchemasMatchTheDocumentedContract() throws Exception {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.at("/components/schemas");

        assertThat(fieldNames(schemas, "MonthlyInsightResponse")).containsExactlyInAnyOrder(
                "periodMonth", "totalIncome", "totalExpense", "netAmount", "summary", "advice",
                "generatedBy", "model", "flaggedCategories", "generatedAt");
        assertThat(fieldNames(schemas, "MonthlyInsightResponse"))
                .doesNotContain("userId", "user_id", "email", "id", "insightId",
                        "status", "errorMessage", "error_message", "flagged_categories");

        assertThat(fieldNames(schemas, "InsightMonthsResponse")).containsExactlyInAnyOrder("months");
        assertThat(fieldNames(schemas, "InsightMonthsResponse"))
                .doesNotContain("userId", "user_id", "total", "page", "offset");

        assertThat(fieldNames(schemas, "FlaggedCategoryResponse")).containsExactlyInAnyOrder(
                "categoryId", "categoryName", "currentTotal", "baselineAvg", "pctChange");
        assertThat(fieldNames(schemas, "FlaggedCategoryResponse"))
                .doesNotContain("userId", "user_id", "isSpike", "is_spike", "baselineMonths",
                        "baseline_months", "periodMonth", "type");

        assertThat(fieldNames(schemas, "FlaggedCategoryResponse"))
                .doesNotContain("password", "passwordHash", "password_hash", "resetToken",
                        "reset_token", "sessionToken", "session_token", "refreshToken",
                        "refresh_token", "tokenVersion", "token_version", "email");

        assertThat(enumValues(schemas, "MonthlyInsightResponse", "generatedBy"))
                .containsExactlyInAnyOrder("AI", "RULE_BASED", "MANUAL");

        for (String forbidden : new String[] {"GenerateInsightRequest", "CreateInsightRequest",
                "UpdateInsightRequest", "InsightRequest", "FlagCategoryRequest",
                "SetInsightRequest", "DeleteInsightRequest", "InsightIdResponse"}) {
            assertThat(schemas.has(forbidden))
                    .as("%s must not exist: UC-17 gives a client no way to author an insight", forbidden)
                    .isFalse();
        }
    }

    private JsonNode openApiDocument() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static Set<String> fieldNames(JsonNode schemas, String schemaName) {
        JsonNode properties = schemas.at("/" + schemaName + "/properties");
        assertThat(properties.isObject())
                .as("schema %s must exist with properties", schemaName)
                .isTrue();
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        properties.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        return fields.keySet();
    }

    private static Set<String> enumValues(JsonNode schemas, String schemaName, String propertyName) {
        JsonNode values = schemas.at("/" + schemaName + "/properties/" + propertyName + "/enum");
        assertThat(values.isArray())
                .as("%s.%s must publish its enum members", schemaName, propertyName)
                .isTrue();
        Set<String> members = new TreeSet<>();
        values.forEach(value -> members.add(value.asText()));
        return members;
    }

    private static void collectSchemaReferences(JsonNode node, Set<String> found) {
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null) {
                String value = reference.asText();
                found.add(value.substring(value.lastIndexOf('/') + 1));
            }
            node.fields().forEachRemaining(entry -> collectSchemaReferences(entry.getValue(), found));
        } else if (node.isArray()) {
            node.forEach(element -> collectSchemaReferences(element, found));
        }
    }

    private static boolean isOperation(String field) {
        return switch (field) {
            case "get", "post", "put", "patch", "delete", "head", "options", "trace" -> true;
            default -> false;
        };
    }
}
