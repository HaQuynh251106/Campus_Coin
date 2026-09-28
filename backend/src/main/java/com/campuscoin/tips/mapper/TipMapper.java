package com.campuscoin.tips.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipMonthsResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.entity.TipRow;
import com.campuscoin.tips.entity.UserTip;

@Component
public class TipMapper {

    public TipListResponse toListResponse(LocalDate periodMonth, List<TipRow> rows) {
        return new TipListResponse(
                toMonthString(periodMonth),
                rows.stream().map(this::toTipResponse).toList());
    }

    public TipResponse toTipResponse(UserTip tip) {
        return new TipResponse(
                tip.getId(),
                tip.getCategoryId(),
                tip.getTitle(),
                tip.getBody(),
                tip.getPotentialSaving(),
                tip.getState());
    }

    public TipMonthsResponse toMonthsResponse(List<LocalDate> months) {
        return new TipMonthsResponse(months.stream().map(this::toMonthString).toList());
    }

    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }

    private TipResponse toTipResponse(TipRow row) {
        return new TipResponse(
                row.tipId(),
                row.categoryId(),
                row.title(),
                row.body(),
                row.potentialSaving(),
                row.state());
    }
}
