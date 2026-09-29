package com.shawn.stopscroll.data

data class UsageRecord(
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val userGoal: String,
    val durationMinutes: Int,
    val timestamp: Long,
    val dateStr: String,  // e.g. "2026-09-29"
    val monthStr: String  // e.g. "2026-09"
)

data class DaySummary(
    val dateStr: String,
    val totalMinutes: Int,
    val count: Int
) {
    val hours: Int get() = totalMinutes / 60
    val remainingMinutes: Int get() = totalMinutes % 60
    val formattedDuration: String get() {
        return if (hours > 0) {
            "${hours}小时${remainingMinutes}分钟"
        } else {
            "${totalMinutes}分钟"
        }
    }
}

data class MonthSummary(
    val monthStr: String,
    val totalMinutes: Int,
    val count: Int
) {
    val hours: Int get() = totalMinutes / 60
    val remainingMinutes: Int get() = totalMinutes % 60
    val formattedDuration: String get() {
        return if (hours > 0) {
            "${hours}小时${remainingMinutes}分钟"
        } else {
            "${totalMinutes}分钟"
        }
    }
}
