/**
 * Module 12 (UC-25) — the next-month projection.
 *
 * The projection is the average of the student's recent complete months, and those months come with
 * it as `recentMonths`. Showing the evidence is the point: the screen presents the projection as an
 * average of the months listed, never as a promise. There is no `?month=` parameter — the endpoint
 * is always about the month in progress and the one after it.
 */

/** One complete month the projection averaged over. */
export interface ForecastMonth {
  periodMonth: string;
  income: number;
  expense: number;
  net: number;
}

/** The month in progress, as recorded so far. Absent when nothing is recorded yet. */
export interface CurrentMonthTotals {
  income: number;
  expense: number;
  net: number;
}

/** The projection for the next month. Absent when there is no complete month to average. */
export interface ProjectedMonth {
  income: number;
  expense: number;
  /** Projected income minus projected spending. Negative when spending is set to outrun income. */
  savings: number;
}

export interface ForecastResponse {
  /** The month projected for, `yyyy-MM` — the month after the one in progress. */
  nextMonth: string;
  currentMonth: string;
  /** How many complete months the projection averaged over. `0` when there is no projection. */
  basedOnMonths: number;
  /** The evidence, oldest first. Empty for a student with no complete month behind them. */
  recentMonths: ForecastMonth[];
  currentMonthTotals?: CurrentMonthTotals;
  projected?: ProjectedMonth;
}
