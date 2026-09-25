package com.campuscoin.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.transaction.entity.TransactionSource;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One transaction as the client sees it (UC-07, UC-10).
 *
 * <p><b>{@code type} is derived, and the DTO documents it as such.</b> There is no {@code type}
 * column on {@code transactions}: the value here is {@code categories.type} for the category the
 * record is filed under (BR-05). It is included because every screen that lists a transaction groups
 * or colours it by income versus expense, and making each client join the category it already has in
 * order to learn one field would be a worse contract. What matters is that it is read, never
 * written - no request type in this module has the field.
 *
 * <p><b>The category is flattened, not nested.</b> The name, icon and colour are sent beside
 * {@code categoryId} rather than as a sub-object, because that is the shape the Angular model
 * already declares ({@code categoryName}, {@code categoryIcon}, {@code categoryColor}) and because a
 * list row renders them without needing anything else about the category. Four fields from one
 * already-fetched row cost nothing; a nested object would add a level the templates do not want.
 *
 * <p><b>What is omitted, and why it matters more here than elsewhere.</b> The AI suggestion columns
 * (UC-08), the anomaly flags (UC-24), the two origin links (UC-09, UC-11) and {@code userId} are all
 * absent. The first two are not built yet, and a response that published a field the module cannot
 * populate would be describing behaviour that does not exist. The ownership field is absent for the
 * usual reason: the client has no use for it and must never be tempted to send one back.
 *
 * <p>{@code createdAt} and {@code updatedAt} are absent as well, matching the category module, whose
 * response carries no creation metadata either. Neither UC-07 nor UC-10 shows them, no screen renders
 * them, and publishing a value nothing reads would only invite a client to depend on it.
 * {@code deletedAt} <em>is</em> published, because it is what makes {@code isDeleted} actionable: a
 * trash view has to be able to say when a record was removed.
 *
 * <p>{@code description} is omitted when unset rather than serialised as null, following the
 * convention the previous two modules set for nullable fields; the Angular model marks it optional.
 */
@Schema(description = "A recorded income or expense (UC-07, UC-10).")
public record TransactionResponse(

        @Schema(description = "Identifier, as the database assigned it.", example = "31")
        Long id,

        @Schema(description = "The category this is filed under.", example = "1")
        Long categoryId,

        @Schema(description = "Name of that category.", example = "Food")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "utensils", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#EF4444", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "Whether this counts as income or expense. Derived from the category "
                + "and read-only: `transactions` has no type column, because keeping one source of "
                + "truth is what guarantees the two agree (BR-05).", example = "EXPENSE")
        CategoryType type,

        @Schema(description = "How much moved. Always positive; `type` says which direction.",
                example = "12.50")
        BigDecimal amount,

        @Schema(description = "The date the money moved.", example = "2026-09-24")
        LocalDate txnDate,

        @Schema(description = "Optional note. Omitted when not set.",
                example = "Lunch with the study group", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "How the record came to exist. Always `MANUAL` for a row created "
                + "through this API; `CSV` (UC-11) and `RECURRING` (UC-09) belong to later modules. "
                + "Read-only: a client able to claim `RECURRING` could record a future-dated "
                + "transaction, since that is the one source exempt from the BR-08 date check.",
                example = "MANUAL")
        TransactionSource source,

        @Schema(description = "True when the record is in the trash (BR-09). It is excluded from "
                + "every balance and report while this is true, and can be brought back with the "
                + "restore endpoint (UC-10 A1).", example = "false")
        Boolean isDeleted,

        @Schema(description = "When the record was deleted. Omitted unless it is deleted.",
                example = "2026-09-25T09:30:00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime deletedAt) {
}
