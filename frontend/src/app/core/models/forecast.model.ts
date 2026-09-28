

export interface ForecastMonth {
  periodMonth: string;
  income: number;
  expense: number;
  net: number;
}

export interface CurrentMonthTotals {
  income: number;
  expense: number;
  net: number;
}

export interface ProjectedMonth {
  income: number;
  expense: number;

  savings: number;
}

export interface ForecastResponse {

  nextMonth: string;
  currentMonth: string;

  basedOnMonths: number;

  recentMonths: ForecastMonth[];
  currentMonthTotals?: CurrentMonthTotals;
  projected?: ProjectedMonth;
}
