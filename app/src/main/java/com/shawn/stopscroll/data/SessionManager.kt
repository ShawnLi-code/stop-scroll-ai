package com.shawn.stopscroll.data

data class ActiveSession(
    val packageName: String,
    val appName: String = "",
    val userGoal: String,
    val startTime: Long,
    val expireTime: Long,
    val maxAllowedMinutes: Int,
    var consecutiveViolations: Int = 0,
    var lastAiCheckTime: Long = 0L,
    var isCheckingAi: Boolean = false
)

object SessionManager {
    @Volatile
    var currentSession: ActiveSession? = null
        private set

    @Volatile
    var isInterceptDialogShowing: Boolean = false

    var onSessionStartedCallback: ((packageName: String, goal: String, minutes: Int) -> Unit)? = null
    var onSessionEndedCallback: (() -> Unit)? = null

    @Synchronized
    fun startSession(
        packageName: String,
        appName: String = "",
        userGoal: String,
        durationMinutes: Int
    ) {
        val now = System.currentTimeMillis()
        val expire = now + durationMinutes * 60 * 1000L
        currentSession = ActiveSession(
            packageName = packageName,
            appName = appName,
            userGoal = userGoal,
            startTime = now,
            expireTime = expire,
            maxAllowedMinutes = durationMinutes
        )
        onSessionStartedCallback?.invoke(packageName, userGoal, durationMinutes)
    }

    /**
     * 结算当前活跃 Session：
     * 精确计算用户实际在前台使用的秒数/分钟数并存入数据库，绝不提前虚增！
     * 若用户提前退出，立即按实际分钟结算，停止一切后台计时；
     * 若使用时间不足 25 秒（如误触、秒关），按 0 分钟处理，不浪费用户的宝贵预算。
     * @return 实际结算的分钟数 (>=0)
     */
    @Synchronized
    fun settleAndEndSession(reason: String = "正常结束"): Int {
        val session = currentSession ?: return 0
        currentSession = null
        onSessionEndedCallback?.invoke()

        val elapsedMillis = (System.currentTimeMillis() - session.startTime).coerceAtLeast(0L)
        val elapsedSeconds = elapsedMillis / 1000L

        // 时长换算逻辑：
        // 1. 低于 25 秒：忽略不计（不扣配额）
        // 2. 25 秒及以上：四舍五入换算实际分钟，最高不超过设定的单次上限
        val actualMinutes = if (elapsedSeconds < 25L) {
            0
        } else {
            val mins = ((elapsedSeconds + 30L) / 60L).toInt()
            mins.coerceIn(1, session.maxAllowedMinutes)
        }

        if (actualMinutes > 0) {
            RecordManager.addRecord(
                packageName = session.packageName,
                appName = session.appName.ifBlank { session.packageName },
                userGoal = session.userGoal,
                durationMinutes = actualMinutes
            )
        }

        return actualMinutes
    }

    @Synchronized
    fun endSession() {
        settleAndEndSession("直接结束")
    }

    @Synchronized
    fun isSessionActiveFor(packageName: String): Boolean {
        val s = currentSession ?: return false
        if (s.packageName != packageName) return false
        if (System.currentTimeMillis() >= s.expireTime) {
            return false
        }
        return true
    }

    @Synchronized
    fun isExpired(): Boolean {
        val s = currentSession ?: return true
        return System.currentTimeMillis() >= s.expireTime
    }

    @Synchronized
    fun getRemainingSeconds(): Long {
        val s = currentSession ?: return 0L
        val diff = s.expireTime - System.currentTimeMillis()
        return if (diff > 0) diff / 1000 else 0L
    }
}
