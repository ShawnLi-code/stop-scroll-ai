package com.shawn.stopscroll

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.app.TimePickerDialog
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shawn.stopscroll.ai.AiService
import com.shawn.stopscroll.data.PrefManager
import com.shawn.stopscroll.databinding.ActivityMainBinding
import com.shawn.stopscroll.service.StopScrollAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isProgrammaticCheckChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateMonitoredSummary()
        updateCurfewDisplay()
        updateTodayUsageDashboard()
    }

    override fun onPause() {
        super.onPause()
        saveAllConfig()
    }

    private fun initViews() {
        // 0. Today Usage Dashboard
        updateTodayUsageDashboard()

        // 1. Curfew Views
        binding.switchCurfew.isChecked = PrefManager.curfewEnabled
        updateCurfewDisplay()

        // 2. Duration Views
        val defaultMin = PrefManager.defaultDurationMinutes
        when (defaultMin) {
            3 -> binding.mainChip3.isChecked = true
            5 -> binding.mainChip5.isChecked = true
            15 -> binding.mainChip15.isChecked = true
            30 -> binding.mainChip30.isChecked = true
            60 -> binding.mainChip60.isChecked = true
            else -> {
                binding.chipGroupMainDuration.clearCheck()
                binding.etMainDefaultMinutes.setText(defaultMin.toString())
            }
        }

        // 2.1 Daily Limit & AI Macro Audit Views
        binding.switchAiMacroAudit.isChecked = PrefManager.aiMacroAuditEnabled
        val dailyLimit = PrefManager.dailyLimitMinutes
        when (dailyLimit) {
            30 -> binding.limitChip30.isChecked = true
            60 -> binding.limitChip60.isChecked = true
            90 -> binding.limitChip90.isChecked = true
            120 -> binding.limitChip120.isChecked = true
            else -> {
                binding.chipGroupDailyLimit.clearCheck()
                binding.etCustomDailyLimit.setText(dailyLimit.toString())
            }
        }

        // 3. Load AI Config
        binding.etApiKey.setText(PrefManager.apiKey)
        binding.etBaseUrl.setText(PrefManager.baseUrl)
        binding.etModelName.setText(PrefManager.modelName)

        // 4. Load Monitored Packages and WeChat Sub-features Checkboxes
        binding.cbWechatFinder.isChecked = PrefManager.wechatFinderEnabled
        binding.cbWechatMoments.isChecked = PrefManager.wechatMomentsEnabled
        val monitored = PrefManager.monitoredPackages
        binding.cbXhs.isChecked = monitored.contains("com.xingin.xhs")
        binding.cbDouyin.isChecked = monitored.contains("com.ss.android.ugc.aweme")
        binding.cbBilibili.isChecked = monitored.contains("tv.danmaku.bili")
        binding.cbKuaishou.isChecked = monitored.contains("com.smile.gifmaker")
        binding.cbWeibo.isChecked = monitored.contains("com.sina.weibo")
        binding.cbTwitter.isChecked = monitored.contains("com.twitter.android")
        binding.cbChrome.isChecked = monitored.contains("com.android.chrome")

        updateMonitoredSummary()
    }

    private fun initListeners() {
        // Permission jumps
        binding.btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到【别刷了 AI】并开启服务", Toast.LENGTH_LONG).show()
        }

        binding.btnOpenOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "当前系统版本默认支持悬浮窗", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBatteryIgnore.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "已处于电池无限制白名单中！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Curfew Listeners with Anti-Relapse Night Lock
        binding.switchCurfew.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticCheckChange) return@setOnCheckedChangeListener

            if (isChecked) {
                PrefManager.curfewEnabled = true
                updateCurfewDisplay()
                Toast.makeText(this, "🌙 已开启夜间防沉迷宵禁模式", Toast.LENGTH_SHORT).show()
            } else {
                // 用户试图关闭宵禁
                // 1. 夜间宵禁时段内：刚性绝对锁定，禁止关闭！
                if (PrefManager.isInCurfew()) {
                    isProgrammaticCheckChange = true
                    binding.switchCurfew.isChecked = true
                    isProgrammaticCheckChange = false

                    AlertDialog.Builder(this)
                        .setTitle("🌙 夜间防沉迷作息锁定中")
                        .setMessage("现在正是夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n昨晚就是因为解除限制导致刷到了凌晨1点！为了坚守自律底线，夜间时段内严禁关闭宵禁！\n\n请立刻放下手机，保持健康睡眠！")
                        .setPositiveButton("坚守底线，去睡觉", null)
                        .show()
                    return@setOnCheckedChangeListener
                }

                // 2. 白天时段：需通过 AI 解除理由审核
                showUnlockReasonDialog(
                    actionTitle = "关闭夜间防沉迷宵禁",
                    onApproved = {
                        PrefManager.curfewEnabled = false
                        updateCurfewDisplay()
                        Toast.makeText(this, "⚪ 已关闭夜间宵禁", Toast.LENGTH_SHORT).show()
                    },
                    onRejected = {
                        isProgrammaticCheckChange = true
                        binding.switchCurfew.isChecked = true
                        isProgrammaticCheckChange = false
                    }
                )
            }
        }

        binding.btnCurfewStart.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    PrefManager.curfewStartHour = hourOfDay
                    PrefManager.curfewStartMinute = minute
                    updateCurfewDisplay()
                    Toast.makeText(this, "已将宵禁开始时间更新为 %02d:%02d".format(hourOfDay, minute), Toast.LENGTH_SHORT).show()
                },
                PrefManager.curfewStartHour,
                PrefManager.curfewStartMinute,
                true
            ).show()
        }

        binding.btnCurfewEnd.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    PrefManager.curfewEndHour = hourOfDay
                    PrefManager.curfewEndMinute = minute
                    updateCurfewDisplay()
                    Toast.makeText(this, "已将宵禁结束时间更新为 %02d:%02d".format(hourOfDay, minute), Toast.LENGTH_SHORT).show()
                },
                PrefManager.curfewEndHour,
                PrefManager.curfewEndMinute,
                true
            ).show()
        }

        // Duration Listeners
        binding.chipGroupMainDuration.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) {
                binding.etMainDefaultMinutes.text.clear()
                val min = when (checkedId) {
                    binding.mainChip3.id -> 3
                    binding.mainChip5.id -> 5
                    binding.mainChip15.id -> 15
                    binding.mainChip30.id -> 30
                    binding.mainChip60.id -> 60
                    else -> 15
                }
                PrefManager.defaultDurationMinutes = min
                val desc = if (min == 60) "1小时" else "${min}分钟"
                Toast.makeText(this, "⏱️ 默认单次时长已设为: $desc", Toast.LENGTH_SHORT).show()
            }
        }

        binding.etMainDefaultMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.chipGroupMainDuration.clearCheck()
            }
        }

        // Daily Limit & Macro Audit Listeners
        binding.switchAiMacroAudit.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.aiMacroAuditEnabled = isChecked
            val status = if (isChecked) "已开启" else "已关闭"
            Toast.makeText(this, "🧠 AI 每日综合自律监控$status", Toast.LENGTH_SHORT).show()
        }

        binding.chipGroupDailyLimit.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) {
                binding.etCustomDailyLimit.text.clear()
                val limit = when (checkedId) {
                    binding.limitChip30.id -> 30
                    binding.limitChip60.id -> 60
                    binding.limitChip90.id -> 90
                    binding.limitChip120.id -> 120
                    else -> 60
                }
                PrefManager.dailyLimitMinutes = limit
                val desc = if (limit >= 60 && limit % 60 == 0) "${limit / 60}小时" else "${limit}分钟"
                Toast.makeText(this, "🎯 每日自律上限已设为: $desc", Toast.LENGTH_SHORT).show()
            }
        }

        binding.etCustomDailyLimit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.chipGroupDailyLimit.clearCheck()
            }
        }

        // 🎁 免费模型一键预设监听
        binding.chipPresetZhipu.setOnClickListener {
            binding.etBaseUrl.setText("https://open.bigmodel.cn/api/paas/v4")
            binding.etModelName.setText("glm-4-flash")
            binding.tvAiPresetGuide.text = "🌟 已选【智谱 GLM-4-Flash】永久免费！\n国内直连无需梯子，速度极快（200ms）。请前往 open.bigmodel.cn 手机号登录，在 API Keys 复制免费 Key 填入上方。"
            Toast.makeText(this, "🌟 已载入智谱 GLM-4-Flash (国内永久免费)", Toast.LENGTH_SHORT).show()
        }

        binding.chipPresetSilicon.setOnClickListener {
            binding.etBaseUrl.setText("https://api.siliconflow.cn/v1")
            binding.etModelName.setText("Qwen/Qwen2.5-7B-Instruct")
            binding.tvAiPresetGuide.text = "⚡ 已选【硅基流动 Qwen2.5】免费专区！\n国内直连，请在 siliconflow.cn 免费获取 API Key 填入上方即可使用。"
            Toast.makeText(this, "⚡ 已载入硅基流动 Qwen2.5 免费模型", Toast.LENGTH_SHORT).show()
        }

        binding.chipPresetGemini.setOnClickListener {
            binding.etBaseUrl.setText("https://generativelanguage.googleapis.com/v1beta/openai")
            binding.etModelName.setText("gemini-1.5-flash")
            binding.tvAiPresetGuide.text = "🚀 已选【Google Gemini 1.5 Flash】官方免费！\n自带每天1500次免费额度。请填入 Google AI Studio 获取的 API Key。"
            Toast.makeText(this, "🚀 已载入 Google Gemini 官方免费配置", Toast.LENGTH_SHORT).show()
        }

        binding.chipPresetDeepseek.setOnClickListener {
            binding.etBaseUrl.setText("https://api.deepseek.com/v1")
            binding.etModelName.setText("deepseek-chat")
            binding.tvAiPresetGuide.text = "🧠 已选【DeepSeek-V3】官方配置。\n请填入你在 platform.deepseek.com 申请的 API Key。"
            Toast.makeText(this, "🧠 已载入 DeepSeek 官方配置", Toast.LENGTH_SHORT).show()
        }

        // Test AI Connection
        binding.btnTestAi.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val url = binding.etBaseUrl.text.toString().trim()
            val model = binding.etModelName.text.toString().trim()

            if (key.isEmpty()) {
                Toast.makeText(this, "请先输入 API Key！(选配，不配也可正常拦截)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnTestAi.isEnabled = false
            binding.btnTestAi.text = "测试中..."

            lifecycleScope.launch {
                val res = AiService.testConnection(key, url, model)
                binding.btnTestAi.isEnabled = true
                binding.btnTestAi.text = "测试 AI 接口连通性"

                res.fold(
                    onSuccess = { msg ->
                        saveAllConfig()
                        Toast.makeText(this@MainActivity, "✅ $msg (已自动保存)", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(this@MainActivity, "❌ 连接失败: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        // WeChat Sub-features Listeners
        binding.cbWechatFinder.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticCheckChange) return@setOnCheckedChangeListener
            if (isChecked) {
                PrefManager.wechatFinderEnabled = true
                updateMonitoredSummary()
                Toast.makeText(this, "✅ 已开启【微信视频号】自律拦截", Toast.LENGTH_SHORT).show()
            } else {
                if (PrefManager.isInCurfew()) {
                    isProgrammaticCheckChange = true
                    binding.cbWechatFinder.isChecked = true
                    isProgrammaticCheckChange = false
                    showNightLockCurfewDialog()
                    return@setOnCheckedChangeListener
                }
                showUnlockReasonDialog(
                    actionTitle = "解除对【微信视频号】的自律监控",
                    onApproved = {
                        PrefManager.wechatFinderEnabled = false
                        updateMonitoredSummary()
                        Toast.makeText(this, "⚪ 已解除【微信视频号】监控", Toast.LENGTH_SHORT).show()
                    },
                    onRejected = {
                        isProgrammaticCheckChange = true
                        binding.cbWechatFinder.isChecked = true
                        isProgrammaticCheckChange = false
                    }
                )
            }
        }

        binding.cbWechatMoments.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticCheckChange) return@setOnCheckedChangeListener
            if (isChecked) {
                PrefManager.wechatMomentsEnabled = true
                updateMonitoredSummary()
                Toast.makeText(this, "✅ 已开启【微信朋友圈】自律拦截", Toast.LENGTH_SHORT).show()
            } else {
                if (PrefManager.isInCurfew()) {
                    isProgrammaticCheckChange = true
                    binding.cbWechatMoments.isChecked = true
                    isProgrammaticCheckChange = false
                    showNightLockCurfewDialog()
                    return@setOnCheckedChangeListener
                }
                showUnlockReasonDialog(
                    actionTitle = "解除对【微信朋友圈】的自律监控",
                    onApproved = {
                        PrefManager.wechatMomentsEnabled = false
                        updateMonitoredSummary()
                        Toast.makeText(this, "⚪ 已解除【微信朋友圈】监控", Toast.LENGTH_SHORT).show()
                    },
                    onRejected = {
                        isProgrammaticCheckChange = true
                        binding.cbWechatMoments.isChecked = true
                        isProgrammaticCheckChange = false
                    }
                )
            }
        }

        // Checkbox listeners with instant feedback and save
        setupCheckboxListener(binding.cbXhs, "com.xingin.xhs", "小红书")
        setupCheckboxListener(binding.cbDouyin, "com.ss.android.ugc.aweme", "抖音")
        setupCheckboxListener(binding.cbBilibili, "tv.danmaku.bili", "哔哩哔哩")
        setupCheckboxListener(binding.cbKuaishou, "com.smile.gifmaker", "快手")
        setupCheckboxListener(binding.cbWeibo, "com.sina.weibo", "微博")
        setupCheckboxListener(binding.cbTwitter, "com.twitter.android", "Twitter (X)")
        setupCheckboxListener(binding.cbChrome, "com.android.chrome", "Chrome 浏览器")

        // Select Custom Apps Button
        binding.btnSelectCustomApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        // Global Save Button
        binding.btnSaveAll.setOnClickListener {
            saveAllConfig()
            Toast.makeText(this, "💾 全部自律配置与应用监控已成功生效！", Toast.LENGTH_SHORT).show()
        }

        // History Activity jump
        binding.btnOpenHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Check Update Button
        binding.btnCheckUpdate.setOnClickListener {
            checkAppUpdate(isManual = true)
        }
    }

    private fun checkAppUpdate(isManual: Boolean) {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.9"
        } catch (e: Exception) {
            "1.0.9"
        }
        if (isManual) {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "检查中..."
        }

        lifecycleScope.launch {
            val result = com.shawn.stopscroll.update.UpdateManager.checkLatestVersion(currentVersion)
            if (isManual) {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "🔄 检查更新"
            }

            result.fold(
                onSuccess = { info ->
                    if (info.hasNewVersion) {
                        showUpdateAvailableDialog(info)
                    } else {
                        if (isManual) {
                            Toast.makeText(this@MainActivity, "🎉 当前已是最新版本 (v$currentVersion)，无需更新！", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFailure = { e ->
                    if (isManual) {
                        Toast.makeText(this@MainActivity, "检查更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    private fun showUpdateAvailableDialog(info: com.shawn.stopscroll.update.VersionInfo) {
        AlertDialog.Builder(this)
            .setTitle("🎉 发现新版本 ${info.tagName}")
            .setMessage("新版本特性与更新内容：\n\n${info.releaseNotes}\n\n💡 本更新为无损覆盖安装，将自动保留现有全部数据、自律统计及无障碍授权！")
            .setPositiveButton("立即一键更新") { _, _ ->
                startDownloadAndInstall(info.downloadUrl, info.tagName)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun startDownloadAndInstall(downloadUrl: String, tagName: String) {
        @Suppress("DEPRECATION")
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("正在下载 $tagName")
            setMessage("正在加速下载安装包，请稍候...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val res = com.shawn.stopscroll.update.UpdateManager.downloadAndInstallApk(
                activity = this@MainActivity,
                downloadUrl = downloadUrl,
                onProgress = { percent ->
                    progressDialog.progress = percent
                    progressDialog.setMessage("已下载 $percent%...")
                }
            )

            progressDialog.dismiss()

            res.onFailure { err ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("下载更新失败")
                    .setMessage("${err.message}\n\n建议直接访问 GitHub Releases 页面下载。")
                    .setPositiveButton("打开网页下载") { _, _ ->
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ShawnLi-code/stop-scroll-ai/releases"))
                        startActivity(browserIntent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun updateCurfewDisplay() {
        val startStr = "%02d:%02d".format(PrefManager.curfewStartHour, PrefManager.curfewStartMinute)
        val endStr = "%02d:%02d".format(PrefManager.curfewEndHour, PrefManager.curfewEndMinute)
        binding.btnCurfewStart.text = "开始: $startStr"
        binding.btnCurfewEnd.text = "结束: $endStr"

        if (PrefManager.curfewEnabled) {
            binding.tvCurfewSummary.text = "当前生效时段：$startStr 至 $endStr 强制禁止短视频"
            binding.tvCurfewSummary.setTextColor(getColor(R.color.primary))
        } else {
            binding.tvCurfewSummary.text = "当前已停用夜间宵禁"
            binding.tvCurfewSummary.setTextColor(getColor(R.color.text_muted))
        }
    }

    private fun updateTodayUsageDashboard() {
        val today = com.shawn.stopscroll.data.RecordManager.getTodayDateString()
        binding.tvMainTodayDate.text = today
        val summary = com.shawn.stopscroll.data.RecordManager.getTodaySummary()
        binding.tvMainTodayDuration.text = summary.formattedDuration
        binding.tvMainTodayCount.text = "${summary.count} 次"
    }

    private fun setupCheckboxListener(cb: CheckBox, pkg: String, name: String) {
        cb.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticCheckChange) return@setOnCheckedChangeListener

            if (isChecked) {
                val set = PrefManager.monitoredPackages.toMutableSet()
                set.add(pkg)
                PrefManager.monitoredPackages = set
                updateMonitoredSummary()
                Toast.makeText(this, "✅ 已开启【$name】自律拦截", Toast.LENGTH_SHORT).show()
            } else {
                // 用户试图解除对某应用的监控
                // 1. 夜间宵禁时段：严禁解除！
                if (PrefManager.isInCurfew()) {
                    isProgrammaticCheckChange = true
                    cb.isChecked = true
                    isProgrammaticCheckChange = false

                    AlertDialog.Builder(this)
                        .setTitle("🌙 夜间防沉迷作息锁定中")
                        .setMessage("当前处于夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n昨晚就是因为深夜解除限制刷到了凌晨1点！为了杜绝重蹈覆辙，夜间时段内严禁解除任何受控应用！\n\n请放下手机，早点入睡！")
                        .setPositiveButton("坚守底线，去睡觉", null)
                        .show()
                    return@setOnCheckedChangeListener
                }

                // 2. 白天正常时段：提交 AI 审核理由
                showUnlockReasonDialog(
                    actionTitle = "解除对【$name】的自律监控",
                    onApproved = {
                        val set = PrefManager.monitoredPackages.toMutableSet()
                        set.remove(pkg)
                        PrefManager.monitoredPackages = set
                        updateMonitoredSummary()
                        Toast.makeText(this, "⚪ 已解除【$name】监控", Toast.LENGTH_SHORT).show()
                    },
                    onRejected = {
                        isProgrammaticCheckChange = true
                        cb.isChecked = true
                        isProgrammaticCheckChange = false
                    }
                )
            }
        }
    }

    private fun showUnlockReasonDialog(actionTitle: String, onApproved: () -> Unit, onRejected: () -> Unit) {
        val input = EditText(this).apply {
            hint = "请向 AI 说明必须解除限制的紧急/工作事由"
            setPadding(40, 30, 40, 30)
            textSize = 14f
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("🛡️ 自律保护：解除限制理由审核")
            .setMessage("你正在尝试【$actionTitle】。\n为了防止一时冲动导致沉迷破戒，请向 AI 阐述你必须解除限制的正当理由：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("🤖 提交 AI 审查", null)
            .setNegativeButton("取消，保持自律") { d, _ ->
                d.dismiss()
                onRejected()
            }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val reason = input.text.toString().trim()
            if (reason.isEmpty()) {
                Toast.makeText(this, "请输入具体的正当事由！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            input.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "AI 审核中..."

            lifecycleScope.launch {
                val result = AiService.auditUnlockReason(actionTitle, reason)
                if (result.passed) {
                    Toast.makeText(this@MainActivity, "✅ AI 审核通过：已准许解除限制", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    onApproved()
                } else {
                    input.isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "重新提交"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🚫 AI 驳回解除申请！")
                        .setMessage("AI 评语：\n${result.feedback}\n\n已为你维持自律监控开启状态！")
                        .setPositiveButton("保持自律", null)
                        .show()
                    dialog.dismiss()
                    onRejected()
                }
            }
        }
    }

    private fun updateMonitoredSummary() {
        val set = PrefManager.monitoredPackages
        val names = mutableListOf<String>()

        if (PrefManager.wechatFinderEnabled) names.add("微信视频号")
        if (PrefManager.wechatMomentsEnabled) names.add("微信朋友圈")

        if (set.contains("com.xingin.xhs")) names.add("小红书")
        if (set.contains("com.ss.android.ugc.aweme")) names.add("抖音")
        if (set.contains("tv.danmaku.bili")) names.add("B站")
        if (set.contains("com.smile.gifmaker")) names.add("快手")
        if (set.contains("com.sina.weibo")) names.add("微博")
        if (set.contains("com.twitter.android")) names.add("Twitter(X)")
        if (set.contains("com.android.chrome")) names.add("Chrome")

        val presetSet = setOf(
            "com.xingin.xhs", "com.ss.android.ugc.aweme", "tv.danmaku.bili",
            "com.smile.gifmaker", "com.sina.weibo", "com.twitter.android", "com.android.chrome"
        )

        // 自定义从手机选择的其他应用
        val customPackages = set.filter { !presetSet.contains(it) }
        val pm = packageManager

        binding.cgCustomApps.removeAllViews()
        if (customPackages.isNotEmpty()) {
            binding.tvCustomAppsHint.visibility = View.VISIBLE
            for (pkg in customPackages) {
                val appLabel = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    pkg
                }
                names.add(appLabel)

                // 添加带有删除图标的 Chip
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = appLabel
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        if (PrefManager.isInCurfew()) {
                            showNightLockCurfewDialog()
                            return@setOnCloseIconClickListener
                        }
                        showUnlockReasonDialog(
                            actionTitle = "移除对【$appLabel】的自律监控",
                            onApproved = {
                                val s = PrefManager.monitoredPackages.toMutableSet()
                                s.remove(pkg)
                                PrefManager.monitoredPackages = s
                                updateMonitoredSummary()
                                Toast.makeText(this@MainActivity, "⚪ 已移除【$appLabel】监控", Toast.LENGTH_SHORT).show()
                            },
                            onRejected = {}
                        )
                    }
                }
                binding.cgCustomApps.addView(chip)
            }
        } else {
            binding.tvCustomAppsHint.visibility = View.GONE
        }

        if (names.isEmpty()) {
            binding.tvMonitoredSummary.text = "⚠️ 当前未勾选任何监控应用或功能"
            binding.tvMonitoredSummary.setTextColor(getColor(R.color.warning))
        } else {
            binding.tvMonitoredSummary.text = "🎯 当前已受控 (${names.size}项)：${names.joinToString("、")}"
            binding.tvMonitoredSummary.setTextColor(getColor(R.color.primary))
        }
    }

    private fun showNightLockCurfewDialog() {
        AlertDialog.Builder(this)
            .setTitle("🌙 夜间防沉迷作息锁定中")
            .setMessage("当前处于夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n为了避免深夜破戒刷手机导致作息紊乱，夜间宵禁时段内全面刚性锁定，禁止解除任何受控应用或功能！\n\n请立刻放下手机，早点入睡！")
            .setPositiveButton("坚守底线，去睡觉", null)
            .show()
    }

    private fun saveAllConfig() {
        PrefManager.curfewEnabled = binding.switchCurfew.isChecked
        PrefManager.wechatFinderEnabled = binding.cbWechatFinder.isChecked
        PrefManager.wechatMomentsEnabled = binding.cbWechatMoments.isChecked

        val customMin = binding.etMainDefaultMinutes.text.toString().trim().toIntOrNull()
        if (customMin != null && customMin > 0) {
            PrefManager.defaultDurationMinutes = customMin
        }

        PrefManager.aiMacroAuditEnabled = binding.switchAiMacroAudit.isChecked
        val customLimit = binding.etCustomDailyLimit.text.toString().trim().toIntOrNull()
        if (customLimit != null && customLimit > 0) {
            PrefManager.dailyLimitMinutes = customLimit
        }

        PrefManager.apiKey = binding.etApiKey.text.toString().trim()
        PrefManager.baseUrl = binding.etBaseUrl.text.toString().trim()
        PrefManager.modelName = binding.etModelName.text.toString().trim()

        val set = PrefManager.monitoredPackages.toMutableSet()
        if (binding.cbXhs.isChecked) set.add("com.xingin.xhs") else set.remove("com.xingin.xhs")
        if (binding.cbDouyin.isChecked) set.add("com.ss.android.ugc.aweme") else set.remove("com.ss.android.ugc.aweme")
        if (binding.cbBilibili.isChecked) set.add("tv.danmaku.bili") else set.remove("tv.danmaku.bili")
        if (binding.cbKuaishou.isChecked) set.add("com.smile.gifmaker") else set.remove("com.smile.gifmaker")
        if (binding.cbWeibo.isChecked) set.add("com.sina.weibo") else set.remove("com.sina.weibo")
        if (binding.cbTwitter.isChecked) set.add("com.twitter.android") else set.remove("com.twitter.android")
        if (binding.cbChrome.isChecked) set.add("com.android.chrome") else set.remove("com.android.chrome")

        PrefManager.monitoredPackages = set
        updateMonitoredSummary()
    }

    private fun updatePermissionStatuses() {
        // 1. Accessibility Service check
        val isA11yEnabled = isAccessibilityServiceEnabled(this, StopScrollAccessibilityService::class.java)
        if (isA11yEnabled) {
            binding.tvAccessibilityStatus.text = "状态: 已开启运行中 ✓"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.primary))
            binding.btnOpenAccessibility.text = "已开启"
            binding.btnOpenAccessibility.isEnabled = false
        } else {
            binding.tvAccessibilityStatus.text = "状态: 未开启 (点击开启)"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.danger))
            binding.btnOpenAccessibility.text = "去开启"
            binding.btnOpenAccessibility.isEnabled = true
        }

        // 2. Overlay permission check
        val isOverlayEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (isOverlayEnabled) {
            binding.tvOverlayStatus.text = "状态: 已授权 ✓"
            binding.tvOverlayStatus.setTextColor(getColor(R.color.primary))
            binding.btnOpenOverlay.text = "已授权"
            binding.btnOpenOverlay.isEnabled = false
        } else {
            binding.tvOverlayStatus.text = "状态: 未授权"
            binding.tvOverlayStatus.setTextColor(getColor(R.color.danger))
            binding.btnOpenOverlay.text = "去授权"
            binding.btnOpenOverlay.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val serviceInfo = enabledService.resolveInfo.serviceInfo
            if (serviceInfo.packageName == context.packageName && serviceInfo.name == service.name) {
                return true
            }
        }
        return false
    }
}
