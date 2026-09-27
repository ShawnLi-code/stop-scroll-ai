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
    }

    private fun initViews() {
        // Load AI Config
        binding.etApiKey.setText(PrefManager.apiKey)
        binding.etBaseUrl.setText(PrefManager.baseUrl)
        binding.etModelName.setText(PrefManager.modelName)

        // Load Monitored Packages Checkboxes
        val monitored = PrefManager.monitoredPackages
        binding.cbXhs.isChecked = monitored.contains("com.xingin.xhs")
        binding.cbDouyin.isChecked = monitored.contains("com.ss.android.ugc.aweme")
        binding.cbBilibili.isChecked = monitored.contains("tv.danmaku.bili")
        binding.cbKuaishou.isChecked = monitored.contains("com.smile.gifmaker")
        binding.cbWeibo.isChecked = monitored.contains("com.sina.weibo")
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

        // Test AI Connection
        binding.btnTestAi.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val url = binding.etBaseUrl.text.toString().trim()
            val model = binding.etModelName.text.toString().trim()

            if (key.isEmpty()) {
                Toast.makeText(this, "请先输入 API Key！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnTestAi.isEnabled = false
            binding.btnTestAi.text = "测试中..."

            lifecycleScope.launch {
                val res = AiService.testConnection(key, url, model)
                binding.btnTestAi.isEnabled = true
                binding.btnTestAi.text = "测试连接"

                res.fold(
                    onSuccess = { msg ->
                        Toast.makeText(this@MainActivity, "✅ $msg", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(this@MainActivity, "❌ 连接失败: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        // Save Config
        binding.btnSaveConfig.setOnClickListener {
            saveAllConfig()
            Toast.makeText(this, "💾 设置已成功保存！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAllConfig() {
        PrefManager.apiKey = binding.etApiKey.text.toString().trim()
        PrefManager.baseUrl = binding.etBaseUrl.text.toString().trim()
        PrefManager.modelName = binding.etModelName.text.toString().trim()

        val set = mutableSetOf<String>()
        if (binding.cbXhs.isChecked) set.add("com.xingin.xhs")
        if (binding.cbDouyin.isChecked) set.add("com.ss.android.ugc.aweme")
        if (binding.cbBilibili.isChecked) set.add("tv.danmaku.bili")
        if (binding.cbKuaishou.isChecked) set.add("com.smile.gifmaker")
        if (binding.cbWeibo.isChecked) set.add("com.sina.weibo")

        PrefManager.monitoredPackages = set
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
