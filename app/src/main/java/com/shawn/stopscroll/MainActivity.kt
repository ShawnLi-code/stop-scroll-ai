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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shawn.stopscroll.ai.AiService
import com.shawn.stopscroll.data.PrefManager
import com.shawn.stopscroll.databinding.ActivityMainBinding
import com.shawn.stopscroll.service.StopScrollAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
    }

    override fun onPause() {
        super.onPause()
        saveAllConfig()
    }

    private fun initViews() {
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

        // 3. Load AI Config
        binding.etApiKey.setText(PrefManager.apiKey)
        binding.etBaseUrl.setText(PrefManager.baseUrl)
        binding.etModelName.setText(PrefManager.modelName)

        // 4. Load Monitored Packages Checkboxes
        val monitored = PrefManager.monitoredPackages
        binding.cbXhs.isChecked = monitored.contains("com.xingin.xhs")
        binding.cbDouyin.isChecked = monitored.contains("com.ss.android.ugc.aweme")
        binding.cbBilibili.isChecked = monitored.contains("tv.danmaku.bili")
        binding.cbKuaishou.isChecked = monitored.contains("com.smile.gifmaker")
        binding.cbWeibo.isChecked = monitored.contains("com.sina.weibo")
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

        // Curfew Listeners
        binding.switchCurfew.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.curfewEnabled = isChecked
            updateCurfewDisplay()
            val msg = if (isChecked) "🌙 已开启夜间防沉迷宵禁模式" else "⚪ 已关闭夜间宵禁"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        // Checkbox listeners with instant feedback and save
        setupCheckboxListener(binding.cbXhs, "com.xingin.xhs", "小红书")
        setupCheckboxListener(binding.cbDouyin, "com.ss.android.ugc.aweme", "抖音")
        setupCheckboxListener(binding.cbBilibili, "tv.danmaku.bili", "哔哩哔哩")
        setupCheckboxListener(binding.cbKuaishou, "com.smile.gifmaker", "快手")
        setupCheckboxListener(binding.cbWeibo, "com.sina.weibo", "微博")
        setupCheckboxListener(binding.cbChrome, "com.android.chrome", "Chrome 浏览器")

        // Global Save Button
        binding.btnSaveAll.setOnClickListener {
            saveAllConfig()
            Toast.makeText(this, "💾 全部自律配置与应用监控已成功生效！", Toast.LENGTH_SHORT).show()
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

    private fun setupCheckboxListener(cb: CheckBox, pkg: String, name: String) {
        cb.setOnCheckedChangeListener { _, isChecked ->
            val set = PrefManager.monitoredPackages.toMutableSet()
            if (isChecked) {
                set.add(pkg)
                Toast.makeText(this, "✅ 已开启【$name】自律拦截", Toast.LENGTH_SHORT).show()
            } else {
                set.remove(pkg)
                Toast.makeText(this, "⚪ 已解除【$name】监控", Toast.LENGTH_SHORT).show()
            }
            PrefManager.monitoredPackages = set
            updateMonitoredSummary()
        }
    }

    private fun updateMonitoredSummary() {
        val set = PrefManager.monitoredPackages
        val names = mutableListOf<String>()
        if (set.contains("com.xingin.xhs")) names.add("小红书")
        if (set.contains("com.ss.android.ugc.aweme")) names.add("抖音")
        if (set.contains("tv.danmaku.bili")) names.add("B站")
        if (set.contains("com.smile.gifmaker")) names.add("快手")
        if (set.contains("com.sina.weibo")) names.add("微博")
        if (set.contains("com.android.chrome")) names.add("Chrome")

        if (names.isEmpty()) {
            binding.tvMonitoredSummary.text = "⚠️ 当前未勾选任何监控应用"
            binding.tvMonitoredSummary.setTextColor(getColor(R.color.warning))
        } else {
            binding.tvMonitoredSummary.text = "🎯 当前已受控应用 (${names.size}个)：${names.joinToString("、")}"
            binding.tvMonitoredSummary.setTextColor(getColor(R.color.primary))
        }
    }

    private fun saveAllConfig() {
        PrefManager.curfewEnabled = binding.switchCurfew.isChecked

        val customMin = binding.etMainDefaultMinutes.text.toString().trim().toIntOrNull()
        if (customMin != null && customMin > 0) {
            PrefManager.defaultDurationMinutes = customMin
        }

        PrefManager.apiKey = binding.etApiKey.text.toString().trim()
        PrefManager.baseUrl = binding.etBaseUrl.text.toString().trim()
        PrefManager.modelName = binding.etModelName.text.toString().trim()

        val set = mutableSetOf<String>()
        if (binding.cbXhs.isChecked) set.add("com.xingin.xhs")
        if (binding.cbDouyin.isChecked) set.add("com.ss.android.ugc.aweme")
        if (binding.cbBilibili.isChecked) set.add("tv.danmaku.bili")
        if (binding.cbKuaishou.isChecked) set.add("com.smile.gifmaker")
        if (binding.cbWeibo.isChecked) set.add("com.sina.weibo")
        if (binding.cbChrome.isChecked) set.add("com.android.chrome")

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
