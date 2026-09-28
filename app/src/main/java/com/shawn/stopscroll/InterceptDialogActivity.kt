package com.shawn.stopscroll

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shawn.stopscroll.data.SessionManager
import com.shawn.stopscroll.databinding.ActivityInterceptBinding

class InterceptDialogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterceptBinding
    private var targetPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInterceptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INTERCEPT
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""

        if (mode == MODE_ALERT) {
            setupAlertMode()
        } else {
            setupInterceptMode()
        }
    }

    override fun onResume() {
        super.onResume()
        SessionManager.isInterceptDialogShowing = true
    }

    override fun onPause() {
        super.onPause()
        SessionManager.isInterceptDialogShowing = false
    }

    private fun setupAlertMode() {
        binding.layoutInterceptCard.visibility = View.GONE
        binding.layoutAlertCard.visibility = View.VISIBLE

        val icon = intent.getStringExtra(EXTRA_ICON) ?: "🚫"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "使用已被强制中断"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "已为你强制中断！请放下手机。"

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

        binding.btnEnterApp.setOnClickListener {
            val goal = binding.etUserGoal.text.toString().trim()
            if (goal.isEmpty()) {
                Toast.makeText(this, "请先写下你打开的具体目标！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var durationMinutes = 3
            when (binding.chipGroupDuration.checkedChipId) {
                R.id.chip3 -> durationMinutes = 3
                R.id.chip5 -> durationMinutes = 5
                R.id.chip10 -> durationMinutes = 10
                R.id.chip15 -> durationMinutes = 15
            }

            // Start session in SessionManager
            SessionManager.startSession(targetPackage, goal, durationMinutes)
            Toast.makeText(this, "🎯 目标已设定：$goal (${durationMinutes}分钟)，AI已开始守护！", Toast.LENGTH_SHORT).show()

            // Launch the target app
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
            }

            finish()
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

    private fun getAppName(pkg: String): String {
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
