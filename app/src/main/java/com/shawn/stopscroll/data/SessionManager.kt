package com.shawn.stopscroll.data

data class ActiveSession(
    val packageName: String,
    val userGoal: String,
    val startTime: Long,
    val expireTime: Long,
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

    @Synchronized
    fun startSession(packageName: String, userGoal: String, durationMinutes: Int) {
        val now = System.currentTimeMillis()
        val expire = now + durationMinutes * 60 * 1000L
        currentSession = ActiveSession(
            packageName = packageName,
            userGoal = userGoal,
            startTime = now,
            expireTime = expire
        )
    }

    @Synchronized
    fun endSession() {
        currentSession = null
    }

    @Synchronized
    fun isSessionActiveFor(packageName: String): Boolean {
        val s = currentSession ?: return false
        if (s.packageName != packageName) return false
        if (System.currentTimeMillis() >= s.expireTime) {
            currentSession = null
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
