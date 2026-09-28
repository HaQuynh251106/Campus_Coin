package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.TipTemplateResponse;
import com.campuscoin.admin.entity.AdminTipTemplateRow;

@Component
public class AdminTipTemplateMapper {

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

    public List<TipTemplateResponse> toResponses(List<AdminTipTemplateRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
