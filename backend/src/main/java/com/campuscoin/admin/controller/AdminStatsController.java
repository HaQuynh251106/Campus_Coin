package com.campuscoin.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.AdminTopCategoryResponse;
import com.campuscoin.admin.dto.AdminUsageStatsResponse;
import com.campuscoin.admin.service.AdminStatsService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * How the system is being used: UC-23.
 *
 * <p>Two endpoints, both read-only, and they stay separate because they are two shapes rather than two
 * views of one: the first is a single row of scalar totals, the second is a ranking over every
 * category. Merging them would either bury a list inside a summary row or drop the category identity the
 * ranking is about.
 *
 * <p><b>No endpoint here returns one student's figures.</b> Every number is a count or a total across
 * many accounts, which is the whole of what UC-23 asks for. The two money columns are sums over the
 * plaintext amounts the encryption pass deliberately left in the clear (OB-013) - an aggregate that
 * cannot be resolved back to an individual - and no administrator route returns a single student's
 * transactions, budgets or spending.
 *
 * <p><b>Nothing is computed in the service.</b> Both figures come from database views, whose
 * definitions of "student", "active" and "not deleted" are the schema's; a second answer in Java would
 * be a second definition that could drift from the one students' own screens are built on.
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
@Tag(name = "Administration - statistics",
        description = "System-wide usage figures and category rankings (UC-23). Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    public AdminStatsController(AdminStatsService adminStatsService) {
        this.adminStatsService = adminStatsService;
    }

    @GetMapping
    @Operation(
            summary = "System-wide usage figures",
            description = """
                    Returns one row of totals: how many students there are, how many are active and \
                    how many have been disabled, how many accounts have been seen in the last 30 days, \
                    how many transactions have been recorded, the total income and expense logged, how \
                    many spending limits students have set, and how many saving tips have been \
                    generated.

                    The two money figures are **sums across every student**, not any one student's \
                    spending. They are totals over all recorded amounts.

                    `totalTransactions` counts transactions that are not in the trash, matching `BR-09` \
                    - a deleted transaction is excluded from the count, as it is from every other figure \
                    in the system.

                    `totalInsightsGenerated` is always zero in this build: monthly insights are UC-17, \
                    which is not enabled. The field is present because it is a real count of a real \
                    table, and a caller reading this row should see the same numbers the database \
                    computes.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The aggregate row.",
                    content = @Content(schema = @Schema(implementation = AdminUsageStatsResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public AdminUsageStatsResponse usageStats() {
        return adminStatsService.usageStats();
    }

    @GetMapping("/top-categories")
    @Operation(
            summary = "Categories ranked by usage",
            description = """
                    Returns every category with how many transactions have been recorded in it, the \
                    total recorded there and how many distinct students have used it - most-used first, \
                    with a stable order that does not change between two identical calls.

                    **Shared and personal categories both appear**, and `scope` says which: `DEFAULT` \
                    for one every student can choose and `PERSONAL` for one a student created. The \
                    distinction matters when reading a name - "Entertainment" is a different row \
                    depending on which it is.

                    **A category nobody has used is still listed**, with zeroes. That is not noise: \
                    which shared categories no student touches is what retiring one (BR-07) is decided \
                    on, and an unfiltered list is the only place it is visible.

                    Counts cover transactions that are not in the trash (BR-09), as everywhere else.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every category, most-used first."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<AdminTopCategoryResponse> topCategories() {
        return adminStatsService.topCategories();
    }
}
