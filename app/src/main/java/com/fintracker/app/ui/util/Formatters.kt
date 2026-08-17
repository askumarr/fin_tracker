package com.fintracker.app.ui.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MoneyFormat {
    private val inr: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
        currency = Currency.getInstance("INR")
        maximumFractionDigits = 2
    }

    fun formatPaise(paise: Long): String = inr.format(paise / 100.0)

    fun formatCompactPaise(paise: Long): String {
        val rupees = paise / 100.0
        return when {
            rupees >= 100_000 -> "₹${"%.1f".format(Locale.ENGLISH, rupees / 100_000)}L"
            rupees >= 1_000 -> "₹${"%.1f".format(Locale.ENGLISH, rupees / 1_000)}k"
            else -> formatPaise(paise)
        }
    }
}

/**
 * All period math is in Asia/Kolkata (IST). "This month" is the civil calendar month —
 * 1st 00:00:00.000 IST through the last millisecond of the last day of that month —
 * not a rolling 30-day window.
 */
object DateFormatters {
    val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private val dayMonth = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply { timeZone = ist }
    private val dayMonthTime =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).apply { timeZone = ist }
    private val monthYear = SimpleDateFormat("MMM yyyy", Locale.ENGLISH).apply { timeZone = ist }
    private val shortMonth = SimpleDateFormat("MMM", Locale.ENGLISH).apply { timeZone = ist }

    fun day(ms: Long): String = dayMonth.format(Date(ms))
    fun dayTime(ms: Long): String = dayMonthTime.format(Date(ms))
    fun monthYear(ms: Long): String = monthYear.format(Date(ms))
    fun shortMonth(ms: Long): String = shortMonth.format(Date(ms))

    data class MonthPeriod(
        val year: Int,
        /** Calendar month, 1–12 */
        val month: Int,
        val startMs: Long,
        val endMs: Long
    ) {
        val label: String get() = monthLabel(year, month)
        val shortLabel: String get() = shortMonthLabel(month)
        val key: String get() = "%04d-%02d".format(year, month)
    }

    data class YearPeriod(
        val year: Int,
        val startMs: Long,
        val endMs: Long
    ) {
        val label: String get() = year.toString()
    }

    fun nowCalendar(): Calendar = Calendar.getInstance(ist)

    fun currentMonthPeriod(): MonthPeriod {
        val cal = nowCalendar()
        return monthPeriod(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun currentYearPeriod(): YearPeriod {
        val cal = nowCalendar()
        return yearPeriod(cal.get(Calendar.YEAR))
    }

    fun currentMonthRange(): Pair<Long, Long> {
        val period = currentMonthPeriod()
        return period.startMs to period.endMs
    }

    fun monthPeriod(year: Int, month: Int): MonthPeriod {
        require(month in 1..12)
        val cal = Calendar.getInstance(ist)
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis - 1
        return MonthPeriod(year, month, start, end)
    }

    fun yearPeriod(year: Int): YearPeriod {
        val cal = Calendar.getInstance(ist)
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = cal.timeInMillis
        cal.add(Calendar.YEAR, 1)
        val end = cal.timeInMillis - 1
        return YearPeriod(year, start, end)
    }

    fun monthLabel(year: Int, month: Int): String =
        monthYear(monthPeriod(year, month).startMs)

    fun shortMonthLabel(month: Int): String {
        val cal = Calendar.getInstance(ist)
        cal.clear()
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return shortMonth.format(cal.time)
    }

    fun periodDayRangeLabel(startMs: Long, endMs: Long): String {
        val startDay = SimpleDateFormat("d", Locale.ENGLISH).apply { timeZone = ist }
        val endDayMonth = SimpleDateFormat("d MMM", Locale.ENGLISH).apply { timeZone = ist }
        return "${startDay.format(Date(startMs))}–${endDayMonth.format(Date(endMs))} · IST"
    }

    /** Inclusive list of the last [count] calendar months ending with the current month. */
    fun lastNMonths(count: Int): List<MonthPeriod> {
        val current = currentMonthPeriod()
        return (count - 1 downTo 0).map { offset ->
            shiftMonth(current.year, current.month, -offset)
        }
    }

    fun shiftMonth(year: Int, month: Int, delta: Int): MonthPeriod {
        val cal = Calendar.getInstance(ist)
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, delta)
        return monthPeriod(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun monthKeyFor(ms: Long): String {
        val cal = Calendar.getInstance(ist)
        cal.timeInMillis = ms
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun yearFor(ms: Long): Int {
        val cal = Calendar.getInstance(ist)
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR)
    }

    /** Inclusive [start, end] millis for the IST calendar day containing [ms]. */
    fun istDayRange(ms: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance(ist)
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis - 1
        return start to end
    }

    fun dayKey(ms: Long): String {
        val cal = Calendar.getInstance(ist)
        cal.timeInMillis = ms
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
