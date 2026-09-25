package com.campuscoin.admin.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.admin.dto.UpdateDefaultCategoryRequest;
import com.campuscoin.admin.dto.UpsertDefaultCategoryRequest;
import com.campuscoin.admin.service.AdminCategoryService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The shared default categories every student can choose from: UC-20, BR-06.
 *
 * <p>Three endpoints: list, create and edit. There is no {@code DELETE}, because a default category is
 * retired rather than removed - {@code PATCH {"isActive": false}} is BR-07's answer, keeps the name and
 * the history of every transaction filed under it, and is reversible. There is no {@code PUT}: two
 * fields change already-changed values, and the update is a partial one by nature, since the database
 * procedure writes each column as {@code IFNULL(new, old)}.
 *
 * <p><b>{@code GET} exists because no other route can serve it.</b> {@code GET /api/v1/categories} is
 * the student's list: it merges the caller's own categories with the shared ones, so it returns rows an
 * administrator cannot edit and, for an administrator, it would omit nothing but explain nothing. What
 * UC-20 needs is exactly {@code user_id IS NULL} - the shared rows, retired ones included - which is a
 * different question with a different answer.
 *
 * <p><b>The response is the student module's {@link CategoryResponse}.</b> A default category is the
 * same thing to an administrator as to a student - a name, a type, an icon, a colour, whether it is
 * retired - so a second DTO would be a second definition of one row. {@code isDefault} is true on every
 * row returned here, and the mapper publishes no owner, which is what makes reusing it safe.
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@Tag(name = "Administration - categories",
        description = "Manage the shared default categories (UC-20). Administrator only.")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    public AdminCategoryController(AdminCategoryService adminCategoryService) {
        this.adminCategoryService = adminCategoryService;
    }

    @GetMapping
    @Operation(
            summary = "List the shared default categories",
            description = """
                    Returns every shared default category, ordered by type, then display order, then \
                    id.

                    **Retired categories are included.** `isActive` is false on a category an \
                    administrator has withdrawn from students (BR-07), and that is precisely the row \
                    one needs to find in order to bring it back. There is no separate filter for them.

                    Only shared categories are returned: a student's own categories are never listed \
                    here, and neither this endpoint nor the update below can reach one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The shared categories, retired ones included."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<CategoryResponse> listCategories() {
        return adminCategoryService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a shared default category",
            description = """
                    Creates a category every student can immediately choose from, and returns it.

                    A name already used by another shared category of the same type is refused with \
                    `409 CATEGORY_NAME_TAKEN`. The check ignores case, because the \
                    database's collation does - `Food` and `food` are the same name here.

                    `icon`, `color` and `description` are optional and may be omitted; the category is \
                    still created without them. `sortOrder` orders the category within its type, and \
                    `isActive: false` creates it already retired, so students are not offered it until \
                    an administrator turns it on.

                    `type` is what decides whether records filed here count as income or expense \
                    (BR-05), and it is not editable without consequence: once any transaction, budget \
                    or recurring rule uses the category, changing its type is refused - see the update \
                    below.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The category that was created.",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "A shared category of this type already uses the name "
                            + "(CATEGORY_NAME_TAKEN).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategoryResponse createCategory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpsertDefaultCategoryRequest request,
            HttpServletRequest httpRequest) {
        return adminCategoryService.create(
                request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Change a shared default category",
            description = """
                    Changes one or more fields of a shared default category and returns it.

                    **Omit a field to leave it as it is.** There is no way to clear `icon`, `color` or \
                    `description` through this endpoint: omitting them preserves them, and an empty \
                    string is refused rather than treated as a removal, because the database writes \
                    each column as "the new value, or the old one". A blank field is a mistake to \
                    correct, not a value to store.

                    Retiring a category is `isActive: false` (BR-07): students stop being offered it, \
                    it disappears from new records, and every transaction already filed under it keeps \
                    its name and its history. `isActive: true` brings it back.

                    **`type` is refused once the category is in use.** If any transaction, budget or \
                    recurring rule references it, changing income to expense would rewrite what all of \
                    that history means, so `409 CATEGORY_IN_USE` is answered instead. The rule is \
                    enforced by the database, which is where it holds for every caller.

                    **Renaming is allowed and collides like creation does**: a name already used by \
                    another shared category of the same type is `409 CATEGORY_NAME_TAKEN`.

                    An id belonging to a student's own category answers `404`, exactly as an id that \
                    does not exist does - a default category is one that belongs to nobody, and this \
                    endpoint cannot reach anybody's own row.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The category, as it now stands.",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No shared default category has this id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The name is taken (CATEGORY_NAME_TAKEN), the category is in use and "
                            + "its type cannot change (CATEGORY_IN_USE), or it changed during the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategoryResponse updateCategory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateDefaultCategoryRequest request,
            HttpServletRequest httpRequest) {
        return adminCategoryService.update(
                id, request, principal.userId(), AdminRequestContext.clientAddress(httpRequest));
    }
}
