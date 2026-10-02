package com.shawn.stopscroll

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shawn.stopscroll.ai.AiService
import com.shawn.stopscroll.data.PrefManager
import com.shawn.stopscroll.databinding.ActivityAppPickerBinding
import com.shawn.stopscroll.databinding.ItemAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var isMonitored: Boolean
)

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private val allApps = mutableListOf<AppItem>()
    private val displayedApps = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        adapter = AppAdapter(displayedApps) { item ->
            handleAppClick(item)
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val apps = mutableListOf<AppItem>()
                val myPkg = packageName

                for (info in resolveInfos) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == myPkg) continue

                    val name = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    val isMonitored = PrefManager.isMonitored(pkg)

                    apps.add(AppItem(name, pkg, icon, isMonitored))
                }

                // 排序：已监控的排在前面，其余按应用名称字母排序
                apps.sortWith(compareByDescending<AppItem> { it.isMonitored }.thenBy { it.appName })
                apps
            }

            allApps.clear()
            allApps.addAll(list)
            filterApps(binding.etSearchApp.text.toString())
            binding.progressBar.visibility = View.GONE
            binding.tvAppCount.text = "共 ${allApps.size} 款应用"
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        displayedApps.clear()
        if (q.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            displayedApps.addAll(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun handleAppClick(item: AppItem) {
        if (!item.isMonitored) {
            // 勾选监控
            val set = PrefManager.monitoredPackages.toMutableSet()
            set.add(item.packageName)
            PrefManager.monitoredPackages = set
            item.isMonitored = true
            adapter.notifyItemChanged(displayedApps.indexOf(item))
            Toast.makeText(this, "✅ 已开启【${item.appName}】自律拦截", Toast.LENGTH_SHORT).show()
        } else {
            // 解除监控
            // 1. 夜间宵禁保护
            if (PrefManager.isInCurfew()) {
                AlertDialog.Builder(this)
                    .setTitle("🌙 夜间防沉迷作息锁定中")
                    .setMessage("当前处于夜间作息保护时段（${PrefManager.getCurfewTimeDisplay()}）！\n\n为了避免深夜破戒刷手机，夜间时段内严禁解除任何受控应用！\n\n请放下手机，早点入睡！")
                    .setPositiveButton("坚守底线，去睡觉", null)
                    .show()
                return
            }

            // 2. 白天正常时段：提交 AI 审核理由
            showUnlockReasonDialog(item)
        }
    }

    private fun showUnlockReasonDialog(item: AppItem) {
        val input = EditText(this).apply {
            hint = "请向 AI 说明必须解除限制的紧急/工作事由"
            setPadding(40, 30, 40, 30)
            textSize = 14f
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("🛡️ 自律保护：解除限制理由审核")
            .setMessage("你正在尝试解除对【${item.appName}】的自律监控。\n为了防止一时冲动导致沉迷破戒，请向 AI 阐述你必须解除限制的正当理由：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("🤖 提交 AI 审查", null)
            .setNegativeButton("取消，保持自律", null)
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
                val result = AiService.auditUnlockReason("解除对【${item.appName}】的监控", reason)
                if (result.passed) {
                    val set = PrefManager.monitoredPackages.toMutableSet()
                    set.remove(item.packageName)
                    PrefManager.monitoredPackages = set
                    item.isMonitored = false
                    adapter.notifyItemChanged(displayedApps.indexOf(item))
                    Toast.makeText(this@AppPickerActivity, "⚪ 已解除【${item.appName}】监控", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    input.isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "重新提交"
                    AlertDialog.Builder(this@AppPickerActivity)
                        .setTitle("🚫 AI 驳回解除申请！")
                        .setMessage("AI 评语：\n${result.feedback}\n\n已为你维持自律监控开启状态！")
                        .setPositiveButton("保持自律", null)
                        .show()
                    dialog.dismiss()
                }
            }
        }
    }

    class AppAdapter(
        private val items: List<AppItem>,
        private val onItemClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemAppPickerBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.ivAppIcon.setImageDrawable(item.icon)
            holder.binding.tvAppName.text = item.appName
            holder.binding.tvPackageName.text = item.packageName
            holder.binding.cbMonitored.isChecked = item.isMonitored

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
