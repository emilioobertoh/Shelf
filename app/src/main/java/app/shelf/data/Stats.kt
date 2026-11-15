package app.shelf.data

import java.time.LocalDate

/** Everything the goals card shows, computed from reading sessions + goals. */
data class ReadingStats(
    val todayMinutes: Int,
    val dailyGoalMinutes: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    /** Last 7 days, oldest first: true when the goal was met that day. */
    val lastSevenDays: List<Boolean>,
    val booksFinishedThisYear: Int,
    val yearlyGoalBooks: Int,
)

object StatsCalculator {
    fun compute(
        totals: List<DayTotal>,
        goals: Goals,
        finishedThisYear: Int,
        today: LocalDate = LocalDate.now(),
    ): ReadingStats {
        val minutesByDay = totals.associate { it.day to (it.seconds / 60).toInt() }
        val goal = goals.dailyMinutes.coerceAtLeast(1)
        fun met(day: Long) = (minutesByDay[day] ?: 0) >= goal

        val todayDay = today.toEpochDay()

        // Current streak: today counts if met; if not met yet, the streak from yesterday is still alive.
        var day = if (met(todayDay)) todayDay else todayDay - 1
        var current = 0
        while (met(day)) {
            current++
            day--
        }

        var best = 0
        var run = 0
        var previousMetDay: Long? = null
        for (d in minutesByDay.keys.filter(::met).sorted()) {
            run = if (previousMetDay == d - 1) run + 1 else 1
            best = maxOf(best, run)
            previousMetDay = d
        }

        return ReadingStats(
            todayMinutes = minutesByDay[todayDay] ?: 0,
            dailyGoalMinutes = goal,
            currentStreak = current,
            bestStreak = maxOf(best, current),
            lastSevenDays = (6 downTo 0).map { met(todayDay - it) },
            booksFinishedThisYear = finishedThisYear,
            yearlyGoalBooks = goals.yearlyBooks,
        )
    }
}
