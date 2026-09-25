package com.campuscoin.category.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.dto.CreateCategoryRequest;
import com.campuscoin.category.dto.UpdateCategoryRequest;
import com.campuscoin.category.service.CategoryService;
import com.campuscoin.common.exception.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Personal categories: UC-06.
 *
 * <p>Five endpoints. The list returns the student's own categories and the shared defaults together,
 * because that is how they are used - one set of choices in a picker - and the client tells them
 * apart by {@code isDefault}.
 *
 * <p>It deliberately does not sit under {@code /profile/me}. The profile module manages one record
 * per student with no identifier in the path, so every endpoint there is the caller's own by
 * construction. A category is one of many, so these endpoints are addressed by id and ownership
 * cannot be implied by the URL; it is enforced by a query that takes the caller's id as well
 * ({@code CategoryRepository.findByIdAndUserId}), and asserted by a test that has one student try
 * to reach another's category.
 *
 * <p>Every method reads the caller with {@code @AuthenticationPrincipal}, so identity comes from the
 * verified bearer token. No endpoint here accepts a user id, a role or an account status.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories",
        description = "A student's own categories, plus the shared defaults they can use (UC-06).")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(
            summary = "List the categories I can use",
            description = """
                    Returns the shared default categories plus the caller's own, ordered by type, \
                    then display order.

                    `isDefault` is true for a shared default category, which the student can use \
                    but not edit or delete; those are administered by UC-20. `isActive` is false \
                    for a retired category, which keeps its history but should not be offered for \
                    new records (BR-07).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The default categories and the caller's own."),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<CategoryResponse> listCategories(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.listCategories(principal);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get one of my categories",
            description = """
                    Returns one category the caller owns, so a client can refresh a single row \
                    without reloading the whole list.

                    A default category is not reachable here - it belongs to no student - and \
                    neither is another student's, which answers `404` rather than `403` so that \
                    category identifiers cannot be probed.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's category.",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategoryResponse getCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable Long id) {
        return categoryService.getCategory(principal, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a personal category",
            description = """
                    Creates a category owned by the caller. `name` and `type` are required.

                    The name must not repeat one of the caller's existing categories of the same \
                    type, and must not be the name of a shared default category of that type - \
                    otherwise it would be ambiguous which one a picker meant (BR-06).

                    `type` is fixed for the life of the category once anything has been filed \
                    under it, because it is the single source of truth for whether a record is \
                    income or expense (BR-05).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The category that was created.",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The name is already taken (CATEGORY_NAME_TAKEN).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategoryResponse createCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Valid @RequestBody CreateCategoryRequest request) {
        return categoryService.create(principal, request);
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Update one of my categories",
            description = """
                    Changes the supplied fields and leaves the rest as they are. Send only what \
                    you changed.

                    A field sent as `null` is treated as "not changed". To clear `icon`, `color` or \
                    `description`, send an empty string.

                    Changing `type` is refused once a transaction, a budget or a recurring rule \
                    uses the category, because that would rewrite the meaning of those records \
                    (BR-05). Set `isActive` to false to retire a category instead of deleting it \
                    (BR-07).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated category.",
                    content = @Content(schema = @Schema(implementation = CategoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed; see fieldErrors.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The name is taken (CATEGORY_NAME_TAKEN), or the category is "
                            + "already in use (CATEGORY_IN_USE).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public CategoryResponse updateCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return categoryService.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete one of my categories",
            description = """
                    Deletes a category the caller owns, provided nothing refers to it.

                    If a transaction, a budget or a recurring rule already uses the category the \
                    database refuses the delete, and the API answers `409` \
                    (`CATEGORY_IN_USE`). Retire it instead by setting `isActive` to false, so the \
                    existing records keep their category (BR-07).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted."),
            @ApiResponse(responseCode = "404", description = "No such category of the caller's.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The category is still in use (CATEGORY_IN_USE).",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "No token, or the token is invalid, expired or revoked.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void deleteCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable Long id) {
        categoryService.delete(principal, id);
    }
}
