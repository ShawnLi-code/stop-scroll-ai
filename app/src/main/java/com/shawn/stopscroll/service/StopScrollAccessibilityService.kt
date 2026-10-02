package com.shawn.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    // 刚性主动倒计时任务
    private val timeoutRunnable = Runnable {
        handleTimeout()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("StopScroll", "AccessibilityService connected successfully!")

        // 注册主动倒计时与监听回调
        SessionManager.onSessionStartedCallback = { pkg, goal, minutes ->
            scheduleActiveTimer(pkg, goal, minutes)
        }
        SessionManager.onSessionEndedCallback = {
            mainHandler.removeCallbacks(timeoutRunnable)
            aiMonitoringJob?.cancel()
        }

        showToast("【别刷了 AI】守护服务已激活！")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        aiMonitoringJob?.cancel()
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
        val goal = session.userGoal
        val minutes = ((session.expireTime - session.startTime) / 60000L).coerceAtLeast(1)

        Log.i("StopScroll", "⏰ PROACTIVE TIMER FIRED! Time is up for $pkg ($goal)")
        SessionManager.endSession()

        // 强震动提示
        vibrateDevice(800L)

        // 立即强弹超时阻断卡片
        triggerAlert(
            pkg,
            "⏰ 专注时间已到！",
            "你设定的【$goal】(${minutes}分钟) 时长已用尽！\n\n请立刻放下手机，让眼睛和大脑休息一下吧。",
            "⏰"
        )
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
                                SessionManager.endSession()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val pkgName = event.packageName?.toString() ?: return

            // 1. 如果正在显示拦截/告警页面，忽略一切事件，避免死循环
            if (SessionManager.isInterceptDialogShowing) {
                return
            }

            // 2. 检查是否是被监控的应用（小红书、抖音、Chrome等）
            if (!PrefManager.isMonitored(pkgName)) {
                return
            }

            // 3. 🌙 检查是否处于夜间防沉迷宵禁时段！如果处于宵禁，直接强阻断，不允许打开！
            if (PrefManager.isInCurfew()) {
                val now = System.currentTimeMillis()
                if (now - lastInterceptTime > 1500L) {
                    lastInterceptTime = now
                    Log.i("StopScroll", "🌙 Curfew active! Intercepting $pkgName (${PrefManager.getCurfewTimeDisplay()})")
                    vibrateDevice(600L)
                    triggerAlert(
                        pkgName,
                        "🌙 夜间防沉迷宵禁！",
                        "当前已进入夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n夜深了，为了保护睡眠质量与明日精力，此时间段内禁止打开娱乐短视频应用。\n\n请立刻放下手机，好好休息！",
                        "🌙"
                    )
                }
                return
            }

            // 4. 如果当前应用【没有合法 Session】，并且是【窗口打开切换事件】-> 触发意图拦截！
            if (!SessionManager.isSessionActiveFor(pkgName)) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val now = System.currentTimeMillis()
                    // 防抖 1.5 秒
                    if (now - lastInterceptTime > 1500L) {
                        lastInterceptTime = now
                        Log.i("StopScroll", "Intercepting target app open: $pkgName")
                        interceptApp(pkgName)
                    }
                }
                return
            }

            // 5. 当前处于合法使用 Session 中，兜底检查超时（主要依靠 Handler 主动定时器）
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

    override fun onInterrupt() {
        Log.w("StopScroll", "AccessibilityService onInterrupt")
    }

    companion object {
        var instance: StopScrollAccessibilityService? = null
            private set
    }
}
