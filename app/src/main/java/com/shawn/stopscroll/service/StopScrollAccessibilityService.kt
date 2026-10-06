package com.shawn.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.shawn.stopscroll.InterceptDialogActivity
import com.shawn.stopscroll.ai.AiService
import com.shawn.stopscroll.data.PrefManager
import com.shawn.stopscroll.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StopScrollAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInterceptTime = 0L
    private var aiMonitoringJob: Job? = null

    // 缓存输入法软键盘包名，避免打字被误判为离开应用
    private var imePackages = setOf<String>()
    private var lastImeQueryTime = 0L

    // 息屏/锁屏广播监听：手机一锁屏，立刻停止后台计时并按实结算，绝不后台偷跑时间
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                val current = SessionManager.currentSession
                if (current != null) {
                    val appName = current.appName.ifBlank { getAppName(current.packageName) }
                    Log.i("StopScroll", "📱 Screen off detected! Settling active session for ${current.packageName}")
                    val settled = SessionManager.settleAndEndSession("锁屏息屏")
                    if (settled > 0) {
                        Log.i("StopScroll", "Screen off settled: $settled min(s) for $appName")
                    }
                }
            }
        }
    }

    // 刚性主动倒计时任务
    private val timeoutRunnable = Runnable {
        handleTimeout()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("StopScroll", "AccessibilityService connected successfully!")

        // 注册息屏广播接收器
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver, filter)
        } catch (e: Exception) {
            Log.e("StopScroll", "Error registering screenOffReceiver: ${e.message}")
        }

        // 注册主动倒计时与监听回调
        SessionManager.onSessionStartedCallback = { pkg, goal, minutes ->
            scheduleActiveTimer(pkg, goal, minutes)
        }
        SessionManager.onSessionEndedCallback = {
            mainHandler.removeCallbacks(timeoutRunnable)
            aiMonitoringJob?.cancel()
        }

        showToast("【定心】心流守护服务已激活！")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        aiMonitoringJob?.cancel()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 主动刚性倒计时：时间一到，毫秒级主动弹出告警卡片，不依赖任何屏幕触摸事件
     */
    fun scheduleActiveTimer(packageName: String, userGoal: String, durationMinutes: Int) {
        mainHandler.removeCallbacks(timeoutRunnable)
        val delayMillis = durationMinutes * 60 * 1000L
        Log.i("StopScroll", "Scheduled proactive timeout timer for $packageName in $delayMillis ms ($durationMinutes minutes)")
        mainHandler.postDelayed(timeoutRunnable, delayMillis)

        // 启动后台低开销 AI 周期检测（仅当配置了 Key 时每10秒检测一次，无需监听高刷屏幕滚动事件）
        startAiMonitor(packageName, userGoal)
    }

    private fun handleTimeout() {
        aiMonitoringJob?.cancel()
        val session = SessionManager.currentSession ?: return
        val pkg = session.packageName
        val appName = session.appName.ifBlank { getAppName(pkg) }
        val goal = session.userGoal

        Log.i("StopScroll", "⏰ PROACTIVE TIMER FIRED! Time is up for $pkg ($goal)")
        val actualMinutes = SessionManager.settleAndEndSession("专注时长已用尽")
        val minutes = actualMinutes.coerceAtLeast(1)

        // 强震动提示
        vibrateDevice(800L)

        // 查询今日宏观累计使用情况
        val todayStr = com.shawn.stopscroll.data.RecordManager.getTodayDateString()
        val todaySummary = com.shawn.stopscroll.data.RecordManager.getDaySummary(todayStr)
        val todayMinutes = todaySummary.totalMinutes
        val todayCount = todaySummary.count
        val dailyLimit = com.shawn.stopscroll.data.PrefManager.dailyLimitMinutes

        val hours = todayMinutes / 60
        val remMin = todayMinutes % 60
        val todayTimeDesc = if (hours > 0) "${hours}小时${remMin}分钟" else "${todayMinutes}分钟"

        val title: String
        val msg: String
        val icon: String

        if (todayMinutes >= dailyLimit) {
            title = "🛑 今日自律额度已彻底透支！"
            icon = "🛑"
            msg = "你设定的【$goal】(${minutes}分钟) 时长已用尽！\n\n📊 今日累计在受控应用上已消耗【$todayTimeDesc】(已打开${todayCount}次)，已达到或超出每日自律上限(${dailyLimit}分钟)！\n\n🧠 AI 教练诊断：大脑已处于认知疲劳状态，今日禁止再开启娱乐应用刷屏！若确有突发紧急要事，必须提交紧急特批申请。"
        } else {
            val remain = dailyLimit - todayMinutes
            title = "⏰ 专注时间已到！"
            icon = "⏰"
            msg = "你设定的【$goal】(${minutes}分钟) 时长已用尽！\n\n📊 今日累计已使用【$todayTimeDesc】(今日剩余自律额度: ${remain.coerceAtLeast(0)}分钟)。\n\n请立刻放下手机，让眼睛与大脑休息一下吧！"
        }

        // 立即强弹超时阻断卡片
        triggerAlert(pkg, title, msg, icon)
    }

    private fun startAiMonitor(packageName: String, userGoal: String) {
        aiMonitoringJob?.cancel()
        if (PrefManager.apiKey.isBlank()) return

        aiMonitoringJob = serviceScope.launch {
            // 每隔 12 秒主动轻量采样一次屏幕文本内容（带Hash去重与本地过滤，完全不占用120Hz渲染通道，不发热）
            while (SessionManager.isSessionActiveFor(packageName)) {
                delay(12000L)
                val session = SessionManager.currentSession ?: break
                if (session.packageName != packageName) break

                try {
                    val rootNode = rootInActiveWindow ?: continue
                    val extractedTexts = mutableListOf<String>()
                    extractTextFromNodes(rootNode, extractedTexts)
                    val combined = extractedTexts.joinToString(" | ")

                    if (combined.length > 8) {
                        Log.d("StopScroll", "AI checking screen (${extractedTexts.size} nodes): $combined")
                        val result = AiService.checkContentRelevance(userGoal, combined)
                        Log.i("StopScroll", "AI Decision: isOffTarget=${result.isOffTarget}, reason=${result.reason}")

                        if (result.isOffTarget) {
                            session.consecutiveViolations++
                            vibrateDevice(300L)
                            showToast("⚠️ AI 提醒：疑似偏离目标 (${session.consecutiveViolations}/2)\n原因: ${result.reason}")

                            if (session.consecutiveViolations >= 2) {
                                SessionManager.settleAndEndSession("偏离目标强制中断")
                                aiMonitoringJob?.cancel()
                                vibrateDevice(600L)
                                triggerAlert(
                                    packageName,
                                    "🚫 目标偏离！强制中断",
                                    "你声明的目标是：【$userGoal】\n\nAI检测到你正在浏览：\n${result.reason}\n\n已为你强制中断！",
                                    "🚫"
                                )
                                break
                            }
                        } else {
                            session.consecutiveViolations = 0
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("StopScroll", "AI monitor tick error: ${t.message}")
                }
            }
        }
    }

    private fun isImePackage(pkgName: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastImeQueryTime > 30000L || imePackages.isEmpty()) {
            lastImeQueryTime = now
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imePackages = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
            } catch (e: Throwable) {
                // ignore
            }
        }
        return imePackages.contains(pkgName)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val pkgName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""

            // 1. 如果正在显示拦截/告警页面，忽略一切事件，避免死循环
            if (SessionManager.isInterceptDialogShowing) {
                return
            }

            // 2. 🌟 离开受控应用实时检测：当用户切回桌面、离开当前受控应用或切换其他App时，立即按实际使用结算并停止后台计时！
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val currentSession = SessionManager.currentSession
                if (currentSession != null) {
                    val isSystemOrOverlay = pkgName == packageName ||
                            pkgName == "com.android.systemui" ||
                            pkgName == "android" ||
                            isImePackage(pkgName)

                    if (!isSystemOrOverlay) {
                        val stillInCurrentApp = if (currentSession.packageName == "com.tencent.mm:finder") {
                            pkgName == "com.tencent.mm" && (className.contains("plugin.finder") || className.contains("FinderHome"))
                        } else if (currentSession.packageName == "com.tencent.mm:moments") {
                            pkgName == "com.tencent.mm" && (className.contains("plugin.sns") || className.contains("SnsTimeLine"))
                        } else {
                            pkgName == currentSession.packageName
                        }

                        if (!stillInCurrentApp) {
                            val appName = currentSession.appName.ifBlank { getAppName(currentSession.packageName) }
                            val settledMinutes = SessionManager.settleAndEndSession("离开受控应用")
                            Log.i("StopScroll", "User left $appName, settled $settledMinutes min(s)")
                            if (settledMinutes > 0) {
                                showToast("【$appName】已退出，本次实际使用 ${settledMinutes} 分钟，已精准入账！")
                            } else {
                                Log.i("StopScroll", "User quickly exited $appName (<25s), 0 min recorded.")
                            }
                        }
                    }
                }
            }

            // 3. 判别是否是微信子功能（视频号 / 朋友圈）或普通受控应用
            var effectivePkg = pkgName
            if (pkgName == "com.tencent.mm") {
                if (PrefManager.wechatFinderEnabled && (className.contains("plugin.finder") || className.contains("FinderHome"))) {
                    effectivePkg = "com.tencent.mm:finder"
                } else if (PrefManager.wechatMomentsEnabled && (className.contains("plugin.sns") || className.contains("SnsTimeLine"))) {
                    effectivePkg = "com.tencent.mm:moments"
                } else if (!PrefManager.isMonitored("com.tencent.mm")) {
                    // 用户在微信正常聊天、发语音、微信支付，非视频号/朋友圈，完全不拦截！
                    return
                }
            } else if (!PrefManager.isMonitored(pkgName)) {
                return
            }

            // 4. 🌙 检查是否处于夜间防沉迷宵禁时段！如果处于宵禁，直接强阻断，不允许打开！
            if (PrefManager.isInCurfew()) {
                val now = System.currentTimeMillis()
                if (now - lastInterceptTime > 1500L) {
                    lastInterceptTime = now
                    val appDisplayName = when (effectivePkg) {
                        "com.tencent.mm:finder" -> "微信视频号"
                        "com.tencent.mm:moments" -> "微信朋友圈"
                        else -> pkgName
                    }
                    Log.i("StopScroll", "🌙 Curfew active! Intercepting $effectivePkg (${PrefManager.getCurfewTimeDisplay()})")
                    vibrateDevice(600L)
                    triggerAlert(
                        effectivePkg,
                        "🌙 夜间防沉迷宵禁！",
                        "当前已进入夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n夜深了，为了保护睡眠质量与明日精力，此时间段内禁止打开【$appDisplayName】等娱乐应用。\n\n请立刻放下手机，好好休息！",
                        "🌙"
                    )
                }
                return
            }

            // 5. 如果当前应用【没有合法 Session】，并且是【窗口打开切换事件】-> 触发意图拦截或配额免审放行！
            if (!SessionManager.isSessionActiveFor(effectivePkg)) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val now = System.currentTimeMillis()
                    // 防抖 1.5 秒
                    if (now - lastInterceptTime > 1500L) {
                        lastInterceptTime = now

                        // 🌟 检查「配额内自由支配（免审直通）」模式：在预先规划好的时间内无需AI审核！
                        if (PrefManager.quotaFastPassEnabled) {
                            val todayStr = com.shawn.stopscroll.data.RecordManager.getTodayDateString()
                            val appUsedToday = com.shawn.stopscroll.data.RecordManager.getAppTodayUsedMinutes(effectivePkg, todayStr)
                            val appQuota = PrefManager.getAppDailyQuota(effectivePkg)
                            val todaySummary = com.shawn.stopscroll.data.RecordManager.getDaySummary(todayStr)
                            val totalUsed = todaySummary.totalMinutes
                            val totalBudget = PrefManager.dailyLimitMinutes

                            if (appUsedToday < appQuota && totalUsed < totalBudget) {
                                val appRemain = appQuota - appUsedToday
                                val totalRemain = totalBudget - totalUsed
                                val chunkMinutes = minOf(appRemain, totalRemain, 15).coerceAtLeast(1)
                                val appDisplayName = getAppName(effectivePkg)

                                SessionManager.startSession(
                                    packageName = effectivePkg,
                                    appName = appDisplayName,
                                    userGoal = "每日规划配额自由使用",
                                    durationMinutes = chunkMinutes
                                )

                                showToast("🟢 配额免审畅刷：【$appDisplayName】本次上限${chunkMinutes}分钟 (退出即结算，不耗多余时间)")
                                Log.i("StopScroll", "Fast-pass granted for $effectivePkg ($chunkMinutes min)")
                                return
                            }
                        }

                        Log.i("StopScroll", "Intercepting target open: $effectivePkg")
                        interceptApp(effectivePkg)
                    }
                }
                return
            }

            // 6. 当前处于合法使用 Session 中，兜底检查超时（主要依靠 Handler 主动定时器）
            if (SessionManager.isExpired()) {
                handleTimeout()
                return
            }
        } catch (e: Throwable) {
            Log.e("StopScroll", "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    /**
     * 打开意图拦截全屏卡片（绝不按 HOME 键，由卡片全屏占领屏幕）
     */
    private fun interceptApp(packageName: String) {
        try {
            val intent = Intent(this, InterceptDialogActivity::class.java).apply {
                putExtra(InterceptDialogActivity.EXTRA_PACKAGE, packageName)
                putExtra(InterceptDialogActivity.EXTRA_MODE, InterceptDialogActivity.MODE_INTERCEPT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e("StopScroll", "Failed to launch intercept activity: ${e.message}")
        }
    }

    /**
     * 触发全屏超时或违规强阻断页面
     */
    private fun triggerAlert(packageName: String, title: String, message: String, icon: String) {
        try {
            val intent = Intent(this, InterceptDialogActivity::class.java).apply {
                putExtra(InterceptDialogActivity.EXTRA_PACKAGE, packageName)
                putExtra(InterceptDialogActivity.EXTRA_MODE, InterceptDialogActivity.MODE_ALERT)
                putExtra(InterceptDialogActivity.EXTRA_TITLE, title)
                putExtra(InterceptDialogActivity.EXTRA_MESSAGE, message)
                putExtra(InterceptDialogActivity.EXTRA_ICON, icon)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e("StopScroll", "Failed to launch alert activity: ${e.message}")
        }
    }

    private fun extractTextFromNodes(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return

        try {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrEmpty() && text.length >= 2 && !isNoiseWord(text)) {
                result.add(text)
            }
            if (!desc.isNullOrEmpty() && desc.length >= 2 && !isNoiseWord(desc) && desc != text) {
                result.add(desc)
            }

            for (i in 0 until node.childCount) {
                if (result.size > 50) break
                extractTextFromNodes(node.getChild(i), result)
            }
        } catch (e: Throwable) {
            // 节点树变动异常
        }
    }

    private fun isNoiseWord(str: String): Boolean {
        return str.matches(Regex("^(关注|点赞|评论|转发|收藏|分享|我|推荐|发现|同城|消息|搜索|返回|返回上一页|[0-9]+(\\.[0-9]+)?[万wW]?)$"))
    }

    private fun vibrateDevice(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Throwable) {
            Log.e("StopScroll", "Vibrate error: ${e.message}")
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Log.e("StopScroll", "Toast error: ${e.message}")
            }
        }
    }

    private fun getAppName(pkg: String): String {
        return when (pkg) {
            "com.tencent.mm:finder" -> "微信视频号"
            "com.tencent.mm:moments" -> "微信朋友圈"
            "com.xingin.xhs" -> "小红书"
            "com.ss.android.ugc.aweme" -> "抖音"
            "tv.danmaku.bili" -> "哔哩哔哩"
            "com.smile.gifmaker" -> "快手"
            "com.sina.weibo" -> "微博"
            "com.twitter.android" -> "Twitter (X)"
            else -> try {
                val pm = packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w("StopScroll", "AccessibilityService onInterrupt")
    }

    companion object {
        var instance: StopScrollAccessibilityService? = null
            private set
    }
}
