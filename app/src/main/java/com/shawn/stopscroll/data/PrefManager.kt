package com.shawn.stopscroll.data

import android.content.Context
import android.content.SharedPreferences

object PrefManager {
    private const val PREF_NAME = "stop_scroll_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model_name"
    private const val KEY_PACKAGES = "monitored_packages"

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

    var monitoredPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, setOf(
            "com.xingin.xhs",            // 小红书
            "com.ss.android.ugc.aweme",   // 抖音
            "tv.danmaku.bili",           // 哔哩哔哩
            "com.smile.gifmaker",        // 快手
            "com.sina.weibo"             // 微博
        )) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    fun isMonitored(packageName: String): Boolean {
        return monitoredPackages.contains(packageName)
    }
}
