package com.shawn.stopscroll

import android.content.Intent
import android.os.Bundle
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

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""

        val appName = getAppName(targetPackage)
        binding.tvTargetAppInfo.text = "检测到你正在打开：$appName"

        // Setup Chips
        binding.btnEnterApp.setOnClickListener {
            val goal = binding.etUserGoal.text.toString().trim()
            if (goal.isEmpty()) {
                Toast.makeText(this, "请先写下你打开的具体目标！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var durationMinutes = 5
            when (binding.chipGroupDuration.checkedChipId) {
                R.id.chip3 -> durationMinutes = 3
                R.id.chip5 -> durationMinutes = 5
                R.id.chip10 -> durationMinutes = 10
                R.id.chip15 -> durationMinutes = 15
            }

            // Start session in SessionManager
            SessionManager.startSession(targetPackage, goal, durationMinutes)

            Toast.makeText(this, "🎯 目标已设定：$goal (${durationMinutes}分钟)，AI已开始守护！", Toast.LENGTH_LONG).show()

            // Launch the target app
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }

            finish()
        }

        binding.btnGiveUp.setOnClickListener {
            Toast.makeText(this, "💪 意志力胜出！放下手机，去做更重要的事！", Toast.LENGTH_SHORT).show()
            finish()
        }
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
        // Prevent bypassing simply by pressing back: dismiss and go to home screen
        super.onBackPressed()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package_name"
    }
}
