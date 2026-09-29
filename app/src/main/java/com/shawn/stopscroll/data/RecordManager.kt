package com.shawn.stopscroll.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordManager {
    private const val DB_NAME = "stop_scroll_records.db"
    private const val DB_VERSION = 1

    private const val TABLE_NAME = "usage_records"
    private const val COL_ID = "id"
    private const val COL_PACKAGE = "package_name"
    private const val COL_APP_NAME = "app_name"
    private const val COL_USER_GOAL = "user_goal"
    private const val COL_DURATION = "duration_minutes"
    private const val COL_TIMESTAMP = "timestamp"
    private const val COL_DATE = "date_str"
    private const val COL_MONTH = "month_str"

    private lateinit var dbHelper: DatabaseHelper

    private class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            val sql = """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_PACKAGE TEXT,
                    $COL_APP_NAME TEXT,
                    $COL_USER_GOAL TEXT,
                    $COL_DURATION INTEGER,
                    $COL_TIMESTAMP INTEGER,
                    $COL_DATE TEXT,
                    $COL_MONTH TEXT
                )
            """.trimIndent()
            db.execSQL(sql)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_date ON $TABLE_NAME ($COL_DATE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_month ON $TABLE_NAME ($COL_MONTH)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    fun init(context: Context) {
        dbHelper = DatabaseHelper(context.applicationContext)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

    fun getTodayDateString(): String {
        return dateFormat.format(Date())
    }

    fun getCurrentMonthString(): String {
        return monthFormat.format(Date())
    }

    /**
     * 每次用户带着目标进入应用时，记录一次专注使用
     */
    fun addRecord(
        packageName: String,
        appName: String,
        userGoal: String,
        durationMinutes: Int,
        timestamp: Long = System.currentTimeMillis()
    ): Long {
        val date = Date(timestamp)
        val dateStr = dateFormat.format(date)
        val monthStr = monthFormat.format(date)

        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COL_PACKAGE, packageName)
            put(COL_APP_NAME, appName)
            put(COL_USER_GOAL, userGoal)
            put(COL_DURATION, durationMinutes)
            put(COL_TIMESTAMP, timestamp)
            put(COL_DATE, dateStr)
            put(COL_MONTH, monthStr)
        }
        return db.insert(TABLE_NAME, null, values)
    }

    /**
     * 查询指定日期的所有使用明细记录（按时间倒序）
     */
    fun getRecordsForDate(dateStr: String): List<UsageRecord> {
        val list = mutableListOf<UsageRecord>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            "$COL_DATE = ?",
            arrayOf(dateStr),
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
            val pkgIdx = cursor.getColumnIndexOrThrow(COL_PACKAGE)
            val nameIdx = cursor.getColumnIndexOrThrow(COL_APP_NAME)
            val goalIdx = cursor.getColumnIndexOrThrow(COL_USER_GOAL)
            val durIdx = cursor.getColumnIndexOrThrow(COL_DURATION)
            val timeIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val dateIdx = cursor.getColumnIndexOrThrow(COL_DATE)
            val monthIdx = cursor.getColumnIndexOrThrow(COL_MONTH)

            while (cursor.moveToNext()) {
                list.add(
                    UsageRecord(
                        id = cursor.getLong(idIdx),
                        packageName = cursor.getString(pkgIdx),
                        appName = cursor.getString(nameIdx),
                        userGoal = cursor.getString(goalIdx),
                        durationMinutes = cursor.getInt(durIdx),
                        timestamp = cursor.getLong(timeIdx),
                        dateStr = cursor.getString(dateIdx),
                        monthStr = cursor.getString(monthIdx)
                    )
                )
            }
        }
        return list
    }

    /**
     * 获取指定日期的使用汇总数据（总分钟数与打开次数）
     */
    fun getDaySummary(dateStr: String): DaySummary {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT SUM($COL_DURATION), COUNT(*) FROM $TABLE_NAME WHERE $COL_DATE = ?",
            arrayOf(dateStr)
        )
        cursor.use {
            if (cursor.moveToFirst()) {
                val totalMin = cursor.getInt(0)
                val count = cursor.getInt(1)
                return DaySummary(dateStr, totalMin, count)
            }
        }
        return DaySummary(dateStr, 0, 0)
    }

    /**
     * 获取指定月份的使用汇总数据（总分钟数与打开次数）
     */
    fun getMonthSummary(monthStr: String): MonthSummary {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT SUM($COL_DURATION), COUNT(*) FROM $TABLE_NAME WHERE $COL_MONTH = ?",
            arrayOf(monthStr)
        )
        cursor.use {
            if (cursor.moveToFirst()) {
                val totalMin = cursor.getInt(0)
                val count = cursor.getInt(1)
                return MonthSummary(monthStr, totalMin, count)
            }
        }
        return MonthSummary(monthStr, 0, 0)
    }

    /**
     * 快捷获取今日汇总
     */
    fun getTodaySummary(): DaySummary {
        return getDaySummary(getTodayDateString())
    }
}
