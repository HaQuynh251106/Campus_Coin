package com.campuscoin.common.exception;

/**
 * UC-06: the name is already used by another category the student can see.
 *
 * <p>Two database objects enforce this and neither is duplicated in Java:
 * {@code uk_categories_scope_type_name} stops a student from having two categories of the same
 * name and type, and {@code trg_categories_before_insert} stops a personal category from taking a
 * default category's name. The service checks both first only so the caller receives a precise
 * message; the constraint remains the authority.
 */
public class CategoryNameTakenException extends ApiException {

    public CategoryNameTakenException(String message) {
        super(ErrorCode.CATEGORY_NAME_TAKEN, message);
    }
}
