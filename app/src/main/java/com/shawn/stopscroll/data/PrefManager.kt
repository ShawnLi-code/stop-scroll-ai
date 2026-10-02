package com.shawn.stopscroll.data

import android.content.Context
import android.content.SharedPreferences

object PrefManager {
    private const val PREF_NAME = "stop_scroll_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model_name"
    private const val KEY_PACKAGES = "monitored_packages"
    private const val KEY_CURFEW_ENABLED = "curfew_enabled"
    private const val KEY_CURFEW_START_HOUR = "curfew_start_hour"
    private const val KEY_CURFEW_START_MIN = "curfew_start_min"
    private const val KEY_CURFEW_END_HOUR = "curfew_end_hour"
    private const val KEY_CURFEW_END_MIN = "curfew_end_min"
    private const val KEY_DEFAULT_DURATION = "default_duration_min"
    private const val KEY_CUSTOM_DURATIONS = "custom_durations"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1"
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var modelName: String
        get() = prefs.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat"
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    private const val KEY_WECHAT_FINDER = "wechat_finder_monitored"
    private const val KEY_WECHAT_MOMENTS = "wechat_moments_monitored"

    var wechatFinderEnabled: Boolean
        get() = prefs.getBoolean(KEY_WECHAT_FINDER, true)
        set(value) = prefs.edit().putBoolean(KEY_WECHAT_FINDER, value).apply()

    var wechatMomentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WECHAT_MOMENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_WECHAT_MOMENTS, value).apply()

    var monitoredPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, setOf(
            "com.xingin.xhs",            // 小红书
            "com.ss.android.ugc.aweme",   // 抖音
            "tv.danmaku.bili",           // 哔哩哔哩
            "com.smile.gifmaker",        // 快手
            "com.sina.weibo",            // 微博
            "com.twitter.android",       // Twitter (X)
            "com.android.chrome"         // 浏览器（方便在模拟器/手机快速测试）
        )) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    fun isMonitored(packageName: String): Boolean {
        if (packageName == "com.tencent.mm:finder") return wechatFinderEnabled
        if (packageName == "com.tencent.mm:moments") return wechatMomentsEnabled
        return monitoredPackages.contains(packageName)
    }

    // ========== 夜间防沉迷宵禁配置 (默认 23:00 至 06:00 禁止短视频) ==========
    var curfewEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURFEW_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CURFEW_ENABLED, value).apply()

    var curfewStartHour: Int
        get() = prefs.getInt(KEY_CURFEW_START_HOUR, 23)
        set(value) = prefs.edit().putInt(KEY_CURFEW_START_HOUR, value).apply()

    var curfewStartMinute: Int
        get() = prefs.getInt(KEY_CURFEW_START_MIN, 0)
        set(value) = prefs.edit().putInt(KEY_CURFEW_START_MIN, value).apply()

    var curfewEndHour: Int
        get() = prefs.getInt(KEY_CURFEW_END_HOUR, 6)
        set(value) = prefs.edit().putInt(KEY_CURFEW_END_HOUR, value).apply()

    var curfewEndMinute: Int
        get() = prefs.getInt(KEY_CURFEW_END_MIN, 0)
        set(value) = prefs.edit().putInt(KEY_CURFEW_END_MIN, value).apply()

    fun getCurfewTimeDisplay(): String {
        return String.format("%02d:%02d - %02d:%02d", curfewStartHour, curfewStartMinute, curfewEndHour, curfewEndMinute)
    }

    /**
     * 判断当前时间是否落在夜间防沉迷宵禁时段内
     */
    fun isInCurfew(): Boolean {
        if (!curfewEnabled) return false
        val calendar = java.util.Calendar.getInstance()
        val nowMinutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val startMinutes = curfewStartHour * 60 + curfewStartMinute
        val endMinutes = curfewEndHour * 60 + curfewEndMinute

        return if (startMinutes <= endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            // 跨午夜区间（例如 23:00 至 次日 06:00）
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    // ========== 专注时长配置 (支持 1小时/60分钟、自定义等) ==========
    var defaultDurationMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_DURATION, 15)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_DURATION, value).apply()

    var customDurations: String
        get() = prefs.getString(KEY_CUSTOM_DURATIONS, "3, 5, 15, 30, 60") ?: "3, 5, 15, 30, 60"
        set(value) = prefs.edit().putString(KEY_CUSTOM_DURATIONS, value).apply()

    fun getDurationOptions(): List<Int> {
        val list = customDurations.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
        return if (list.isNotEmpty()) list else listOf(3, 5, 15, 30, 60)
    }
}
