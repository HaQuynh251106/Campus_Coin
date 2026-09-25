package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.TipTemplateResponse;
import com.campuscoin.admin.entity.AdminTipTemplateRow;

/**
 * Maps a saving-tip template row to the API model (UC-21).
 *
 * <p>{@code conditionParams} is not carried by {@link AdminTipTemplateRow}, so nothing here exposes
 * it: the column is a {@code JSON} blob whose meaning the schema does not document and which nothing
 * in this build reads. The exclusion is stated on the row record and in the module report; this class
 * only has to not reintroduce it, which it cannot do, since the record has no component for it.
 *
 * <p>{@code code} is published even though it is immutable. A client editing a template's priority
 * needs it to identify the template it is editing and to round-trip a full representation back -
 * {@code UpdateTipTemplateRequest} accepts the same code as a no-op and refuses a different one - so
 * omitting it would force the client to hold an identifier it was never given.
 */
@Component
public class AdminTipTemplateMapper {

    /**
     * UC-21: one template as the administration screen shows it.
     *
     * <p>Every field is non-null in the schema: {@code condition_type}, {@code default_priority} and
     * {@code is_active} are all {@code NOT NULL}, and the procedure supplies defaults for the ones a
     * caller may omit. The response therefore has no nullable fields, and a null here would mean the
     * projection is wrong rather than that the row has no value.
     */
    public TipTemplateResponse toResponse(AdminTipTemplateRow row) {
        return new TipTemplateResponse(
                row.id(),
                row.code(),
                row.conditionType(),
                row.titleTemplate(),
                row.bodyTemplate(),
                row.defaultPriority(),
                row.isActive());
    }

    /** UC-21: the whole list, mapped row by row through {@link #toResponse}. */
    public List<TipTemplateResponse> toResponses(List<AdminTipTemplateRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
