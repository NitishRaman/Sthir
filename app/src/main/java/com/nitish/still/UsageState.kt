package com.nitish.still

data class UsageState(
    val continuousUsageMs: Long
)

data class WeeklyAppUsage(
    val packageName: String,
    val totalUsage: Long
)

data class DailyUsage(
    val day: String,
    val usageMillis: Long
)
