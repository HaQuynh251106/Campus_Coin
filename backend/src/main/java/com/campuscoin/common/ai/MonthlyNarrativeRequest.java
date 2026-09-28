package com.campuscoin.common.ai;

import java.math.BigDecimal;
import java.util.List;

public record MonthlyNarrativeRequest(String monthLabel,
                                      String currency,
                                      BigDecimal totalIncome,
                                      BigDecimal totalExpense,
                                      BigDecimal netAmount,
                                      List<CategoryTotal> topCategories) {

    public record CategoryTotal(String name, BigDecimal total) {
    }
}
