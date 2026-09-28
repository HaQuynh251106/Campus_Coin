package com.campuscoin.forecast.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthTotals(LocalDate periodMonth, BigDecimal totalIncome, BigDecimal totalExpense) {
}
