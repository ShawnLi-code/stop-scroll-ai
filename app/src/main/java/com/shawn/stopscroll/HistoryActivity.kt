package com.shawn.stopscroll

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.shawn.stopscroll.data.RecordManager
import com.shawn.stopscroll.data.UsageRecord
import com.shawn.stopscroll.databinding.ActivityHistoryBinding
import com.shawn.stopscroll.databinding.ItemUsageRecordBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val selectedCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initListeners()
    }

    override fun onResume() {
        super.onResume()
        loadDataForSelectedDate()
    }

    private fun initListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSelectDate.setOnClickListener {
            val year = selectedCalendar.get(Calendar.YEAR)
            val month = selectedCalendar.get(Calendar.MONTH)
            val day = selectedCalendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(
                this,
                { _, selYear, selMonth, selDay ->
                    selectedCalendar.set(Calendar.YEAR, selYear)
                    selectedCalendar.set(Calendar.MONTH, selMonth)
                    selectedCalendar.set(Calendar.DAY_OF_MONTH, selDay)
                    loadDataForSelectedDate()
                },
                year,
                month,
                day
            ).show()
        }

        binding.btnQuickToday.setOnClickListener {
            selectedCalendar.time = Date()
            loadDataForSelectedDate()
        }

        binding.btnQuickYesterday.setOnClickListener {
            selectedCalendar.time = Date()
            selectedCalendar.add(Calendar.DAY_OF_YEAR, -1)
            loadDataForSelectedDate()
        }

        binding.btnQuickBeforeYesterday.setOnClickListener {
            selectedCalendar.time = Date()
            selectedCalendar.add(Calendar.DAY_OF_YEAR, -2)
            loadDataForSelectedDate()
        }
    }

    private fun loadDataForSelectedDate() {
        val dateStr = dateFormat.format(selectedCalendar.time)
        val monthStr = monthFormat.format(selectedCalendar.time)

        // 1. Update Date Header
        binding.btnSelectDate.text = "📅 $dateStr (点击切换日期)"
        binding.tvSummaryTitle.text = "📊 $dateStr 使用总览"

        // 2. Query Day Summary
        val daySummary = RecordManager.getDaySummary(dateStr)
        binding.tvDailyDuration.text = daySummary.formattedDuration
        binding.tvDailyCount.text = "${daySummary.count} 次"

        // 3. Query Month Summary
        val monthSummary = RecordManager.getMonthSummary(monthStr)
        binding.tvMonthSummary.text = "📆 $monthStr 全月累计受控时长：${monthSummary.formattedDuration} (共计 ${monthSummary.count} 次)"

        // 4. Query & Render Records List
        val records = RecordManager.getRecordsForDate(dateStr)
        binding.layoutRecordContainer.removeAllViews()

        if (records.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvListHeader.text = "📝 $dateStr 无打开记录"
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.tvListHeader.text = "📝 $dateStr 打开明细记录 (${records.size}条)"

            val inflater = LayoutInflater.from(this)
            for (record in records) {
                val itemBinding = ItemUsageRecordBinding.inflate(inflater, binding.layoutRecordContainer, false)
                bindRecordItem(itemBinding, record)
                binding.layoutRecordContainer.addView(itemBinding.root)
            }
        }
    }

    private fun bindRecordItem(itemBinding: ItemUsageRecordBinding, record: UsageRecord) {
        val icon = when {
            record.packageName.contains("aweme") -> "📱"
            record.packageName.contains("xhs") -> "📕"
            record.packageName.contains("bili") -> "📺"
            record.packageName.contains("chrome") -> "🌐"
            record.packageName.contains("weibo") -> "👁️"
            record.packageName.contains("gifmaker") -> "⚡"
            else -> "🎯"
        }

        itemBinding.tvItemAppName.text = "$icon ${record.appName}"
        itemBinding.tvItemTime.text = timeFormat.format(Date(record.timestamp))
        itemBinding.tvItemGoal.text = "🎯 意图目标：${record.userGoal}"

        val durDesc = if (record.durationMinutes >= 60 && record.durationMinutes % 60 == 0) {
            "${record.durationMinutes / 60}小时"
        } else {
            "${record.durationMinutes}分钟"
        }
        itemBinding.tvItemDuration.text = "⏱️ 允许时长：$durDesc"
        itemBinding.tvItemPackage.text = record.packageName
    }
}
