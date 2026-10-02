package com.shawn.stopscroll

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shawn.stopscroll.data.SessionManager
import com.shawn.stopscroll.databinding.ActivityInterceptBinding
import kotlinx.coroutines.launch

class InterceptDialogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterceptBinding
    private var targetPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInterceptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        SessionManager.isInterceptDialogShowing = true
    }

    override fun onPause() {
        super.onPause()
        SessionManager.isInterceptDialogShowing = false
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INTERCEPT
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""

        if (mode == MODE_ALERT) {
            setupAlertMode(intent)
        } else {
            setupInterceptMode()
        }
    }

    private fun setupAlertMode(intent: Intent) {
        binding.layoutInterceptCard.visibility = View.GONE
        binding.layoutAlertCard.visibility = View.VISIBLE

        val icon = intent.getStringExtra(EXTRA_ICON) ?: "⏰"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "专注时间已到！"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "你设定的专注时长已用尽！\n请立刻放下手机，让眼睛和大脑休息一下吧。"

        binding.tvAlertIcon.text = icon
        binding.tvAlertTitle.text = title
        binding.tvAlertMessage.text = message

        binding.btnConfirmExit.setOnClickListener {
            goHomeAndFinish()
        }
    }

    private fun setupInterceptMode() {
        binding.layoutInterceptCard.visibility = View.VISIBLE
        binding.layoutAlertCard.visibility = View.GONE

        val appName = getAppName(targetPackage)
        binding.tvTargetAppInfo.text = "检测到你正在打开：$appName"

        // 加载默认时长配置
        val defaultMin = com.shawn.stopscroll.data.PrefManager.defaultDurationMinutes
        when (defaultMin) {
            3 -> binding.chip3.isChecked = true
            5 -> binding.chip5.isChecked = true
            15 -> binding.chip15.isChecked = true
            30 -> binding.chip30.isChecked = true
            60 -> binding.chip60.isChecked = true
            else -> {
                binding.chipGroupDuration.clearCheck()
                binding.etCustomMinutes.setText(defaultMin.toString())
            }
        }

        binding.chipGroupDuration.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID && binding.etCustomMinutes.hasFocus()) {
                binding.etCustomMinutes.text.clear()
                binding.etCustomMinutes.clearFocus()
            }
        }

        binding.etCustomMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.chipGroupDuration.clearCheck()
            }
        }

        binding.btnEnterApp.setOnClickListener {
            val goal = binding.etUserGoal.text.toString().trim()
            if (goal.isEmpty()) {
                Toast.makeText(this, "请先写下你打开的具体目标！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 先隐藏上一次的打回提示
            binding.layoutGoalReject.visibility = View.GONE
            binding.btnEnterApp.isEnabled = false
            binding.btnEnterApp.text = "🤖 AI 自律教练审查中..."

            lifecycleScope.launch {
                val appName = getAppName(targetPackage)
                val auditResult = com.shawn.stopscroll.ai.AiService.auditGoalReason(appName, goal)

                if (!auditResult.passed) {
                    // ❌ 审核未通过：坚决打回！禁止进入！
                    binding.btnEnterApp.isEnabled = true
                    binding.btnEnterApp.text = "✅ 重新提交正当目标"
                    binding.layoutGoalReject.visibility = View.VISIBLE
                    binding.tvGoalRejectReason.text = auditResult.feedback

                    // 警告震动
                    vibrateWarning()
                    Toast.makeText(this@InterceptDialogActivity, "🚫 目标理由已被打回！请看提示重新输入", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // ✅ 审核通过：放行进入目标应用
                val customText = binding.etCustomMinutes.text.toString().trim()
                val customVal = customText.toIntOrNull()

                val durationMinutes = if (customVal != null && customVal > 0) {
                    customVal
                } else {
                    when (binding.chipGroupDuration.checkedChipId) {
                        R.id.chip3 -> 3
                        R.id.chip5 -> 5
                        R.id.chip15 -> 15
                        R.id.chip30 -> 30
                        R.id.chip60 -> 60
                        else -> 15
                    }
                }

                val durationDesc = if (durationMinutes >= 60 && durationMinutes % 60 == 0) {
                    "${durationMinutes / 60}小时"
                } else {
                    "${durationMinutes}分钟"
                }

                // Start session in SessionManager (which automatically schedules proactive timer!)
                SessionManager.startSession(targetPackage, goal, durationMinutes)

                // 写入本地使用与自律记录
                com.shawn.stopscroll.data.RecordManager.addRecord(
                    packageName = targetPackage,
                    appName = appName,
                    userGoal = goal,
                    durationMinutes = durationMinutes
                )

                Toast.makeText(this@InterceptDialogActivity, "${auditResult.feedback} ($durationDesc)", Toast.LENGTH_SHORT).show()

                // Launch the target app
                if (targetPackage.startsWith("com.tencent.mm:")) {
                    // 微信子功能（视频号/朋友圈）：直接关闭拦截弹窗即可直接回到用户正打开的视频号/朋友圈！
                    finish()
                    return@launch
                }

                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(launchIntent)
                }

                finish()
            }
        }

        binding.btnGiveUp.setOnClickListener {
            Toast.makeText(this, "💪 意志力胜出！放下手机，去做更重要的事！", Toast.LENGTH_SHORT).show()
            goHomeAndFinish()
        }
    }

    private fun goHomeAndFinish() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun vibrateWarning() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(450L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(450L)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getAppName(pkg: String): String {
        if (pkg == "com.tencent.mm:finder") return "微信视频号"
        if (pkg == "com.tencent.mm:moments") return "微信朋友圈"
        if (pkg == "com.twitter.android") return "Twitter (X)"
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            when (pkg) {
                "com.xingin.xhs" -> "小红书"
                "com.ss.android.ugc.aweme" -> "抖音"
                "tv.danmaku.bili" -> "哔哩哔哩"
                "com.smile.gifmaker" -> "快手"
                "com.sina.weibo" -> "微博"
                "com.twitter.android" -> "Twitter (X)"
                "com.android.chrome" -> "Chrome 浏览器"
                else -> pkg
            }
        }
    }

    override fun onBackPressed() {
        goHomeAndFinish()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package_name"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_ICON = "extra_icon"

        const val MODE_INTERCEPT = "INTERCEPT"
        const val MODE_ALERT = "ALERT"
    }
}
