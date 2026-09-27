package com.shawn.stopscroll.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.shawn.stopscroll.InterceptDialogActivity
import com.shawn.stopscroll.R
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundNotification()
        showToast("【别刷了 AI】守护服务已激活！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: return

        // 仅处理用户勾选监控的目标应用（如小红书、抖音等）
        if (!PrefManager.isMonitored(pkgName)) {
            return
        }

        // 检查当前是否有属于该 App 的有效活跃 Session
        if (!SessionManager.isSessionActiveFor(pkgName)) {
            // 没有有效 Session，立即拦截！
            interceptApp(pkgName)
            return
        }

        // 当前处于合法使用 Session 中，检查是否超时或偏离目标
        val session = SessionManager.currentSession ?: return

        // 1. 超时检查
        if (SessionManager.isExpired()) {
            SessionManager.endSession()
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToast("⏰ 时间已到，专注结束！请放下手机休息一下。")
            return
        }

        // 2. AI 动态屏幕内容监督（节流控制：每 12 秒最多检测一次）
        val now = System.currentTimeMillis()
        if (now - session.lastAiCheckTime > 12000L && !session.isCheckingAi) {
            session.lastAiCheckTime = now
            val rootNode = rootInActiveWindow ?: return
            val extractedTexts = mutableListOf<String>()
            extractTextFromNodes(rootNode, extractedTexts)

            val combined = extractedTexts.joinToString(" | ")
            if (combined.length > 15) {
                session.isCheckingAi = true
                serviceScope.launch {
                    try {
                        val result = AiService.checkContentRelevance(session.userGoal, combined)
                        if (result.isOffTarget) {
                            session.consecutiveViolations++
                            showToast("⚠️ AI 提醒：疑似偏离目标 (${session.consecutiveViolations}/2)\n原因: ${result.reason}")

                            if (session.consecutiveViolations >= 2) {
                                // 连续两次检测违规，强制退回桌面
                                SessionManager.endSession()
                                performGlobalAction(GLOBAL_ACTION_HOME)
                                showToast("🚫 严重偏离目标：${result.reason}，已强制退出！")
                            }
                        } else {
                            // 只要处于正轨就清零违规计数
                            session.consecutiveViolations = 0
                        }
                    } finally {
                        session.isCheckingAi = false
                    }
                }
            }
        }
    }

    private fun interceptApp(packageName: String) {
        // 先按 Home 键切出，避免目标 App 内容已经展示出来
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 启动拦截弹窗
        val intent = Intent(this, InterceptDialogActivity::class.java).apply {
            putExtra(InterceptDialogActivity.EXTRA_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun extractTextFromNodes(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length > 2 && !isNoiseWord(text)) {
            result.add(text)
        }
        if (!desc.isNullOrEmpty() && desc.length > 2 && !isNoiseWord(desc) && desc != text) {
            result.add(desc)
        }

        for (i in 0 until node.childCount) {
            if (result.size > 40) break // 避免遍历过深
            extractTextFromNodes(node.getChild(i), result)
        }
    }

    private fun isNoiseWord(str: String): Boolean {
        return str.matches(Regex("^(关注|点赞|评论|转发|收藏|分享|我|推荐|发现|同城|消息|搜索|返回|返回上一页|[0-9]+(\\.[0-9]+)?[万wW]?)$"))
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "stop_scroll_service"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "自律守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持别刷了AI守护服务在后台稳定运行"
            }
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("别刷了 AI 正在守护")
            .setContentText("已开启意图拦截与屏幕防沉迷")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onInterrupt() {
        // 无障碍被中断
    }
}
