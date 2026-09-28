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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StopScrollAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInterceptTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("StopScroll", "AccessibilityService connected successfully!")
        showToast("【别刷了 AI】守护服务已激活！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val pkgName = event.packageName?.toString() ?: return

            // 1. 如果正在显示拦截/告警页面，忽略一切事件，避免死循环
            if (SessionManager.isInterceptDialogShowing) {
                return
            }

            // 2. 检查是否是被监控的应用（小红书、抖音、B站等）
            if (!PrefManager.isMonitored(pkgName)) {
                return
            }

            // 3. 如果当前应用【没有合法 Session】，并且是【窗口打开切换事件】-> 触发意图拦截！
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

            // 4. 当前处于合法使用 Session 中
            val session = SessionManager.currentSession ?: return
            if (session.packageName != pkgName) return

            // A. 超时检查
            if (SessionManager.isExpired()) {
                SessionManager.endSession()
                vibrateDevice(500L)
                triggerAlert(
                    pkgName,
                    "⏰ 专注时间已到！",
                    "设定的时长已用尽，请放下手机，给大脑和眼睛休息一下吧。",
                    "⏰"
                )
                return
            }

            // B. AI 动态屏幕内容监督（节流控制：每 10 秒采样一次）
            val now = System.currentTimeMillis()
            if (now - session.lastAiCheckTime > 10000L && !session.isCheckingAi) {
                session.lastAiCheckTime = now
                val rootNode = rootInActiveWindow ?: return
                val extractedTexts = mutableListOf<String>()
                extractTextFromNodes(rootNode, extractedTexts)

                val combined = extractedTexts.joinToString(" | ")
                Log.d("StopScroll", "Screen texts extracted (${extractedTexts.size} items): $combined")

                // 只要抓到有效文字且配置了 API Key 就发送给 AI 分析
                if (combined.length > 8 && PrefManager.apiKey.isNotBlank()) {
                    session.isCheckingAi = true
                    serviceScope.launch {
                        try {
                            val result = AiService.checkContentRelevance(session.userGoal, combined)
                            Log.i("StopScroll", "AI Decision: isOffTarget=${result.isOffTarget}, reason=${result.reason}")

                            if (result.isOffTarget) {
                                session.consecutiveViolations++
                                vibrateDevice(300L)
                                showToast("⚠️ AI 提醒：疑似偏离目标 (${session.consecutiveViolations}/2)\n原因: ${result.reason}")

                                if (session.consecutiveViolations >= 2) {
                                    // 连续 2 次采样偏离，强制拉出全屏告警阻断！
                                    SessionManager.endSession()
                                    vibrateDevice(600L)
                                    triggerAlert(
                                        pkgName,
                                        "🚫 目标偏离！强制中断",
                                        "你声明的目标是：【${session.userGoal}】\n\nAI检测到你正在浏览：\n${result.reason}\n\n已为你强制中断！",
                                        "🚫"
                                    )
                                }
                            } else {
                                // 只要符合目标，清空违规计数
                                session.consecutiveViolations = 0
                            }
                        } catch (t: Throwable) {
                            Log.e("StopScroll", "AI check error: ${t.message}")
                        } finally {
                            session.isCheckingAi = false
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("StopScroll", "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    /**
     * 打开意图拦截全屏卡片（绝不在此时按 HOME 键，由卡片完全覆盖屏幕并获得焦点）
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
        // 过滤系统纯功能性单字与点赞数字，但保留带#的话题标签
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
}
