package com.shawn.stopscroll.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shawn.stopscroll.data.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AiCheckResult(
    val isOffTarget: Boolean,
    val reason: String
)

data class AiAuditResult(
    val passed: Boolean,
    val feedback: String
)

object AiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // 屏幕内容去重缓存，避免静止或同视频时反复请求消耗电量
    private var lastScreenHash: Int = 0

    /**
     * 测试 API Key 与 Base URL 的连通性
     */
    suspend fun testConnection(apiKey: String, baseUrl: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = cleanUrl(baseUrl) + "/chat/completions"
            val bodyJson = JsonObject().apply {
                addProperty("model", model)
                val messages = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "请回复两个字：成功")
                    })
                }
                add("messages", messages)
                addProperty("max_tokens", 10)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP 状态码: ${response.code}, 响应: ${response.body?.string()}"))
            }

            val respStr = response.body?.string() ?: ""
            val jsonObj = gson.fromJson(respStr, JsonObject::class.java)
            val reply = jsonObj.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString ?: "无返回内容"

            Result.success("连通成功！模型回复: $reply")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 🤖 AI 严格审核用户进入受控应用的目标/理由
     * 坚决打回：单字、乱码、敷衍模糊词汇、纯消遣娱乐沉迷借口
     */
    suspend fun auditGoalReason(appName: String, goal: String): AiAuditResult = withContext(Dispatchers.IO) {
        val trimmed = goal.trim()

        // 1. 本地硬性规则预筛（零耗电极速过滤明显违规）
        val localCheck = localCheckGoal(trimmed)
        if (localCheck != null) {
            return@withContext localCheck
        }

        // 2. 如果未配置 API Key，执行完备的本地智能离线审核
        val apiKey = PrefManager.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext localOfflineSmartAudit(trimmed)
        }

        // 3. 调用 AI 大模型进行严厉的自律教练式语义审查
        try {
            val url = cleanUrl(PrefManager.baseUrl) + "/chat/completions"
            val systemPrompt = """
                你是一个极度严格、富有洞察力的「自律与防沉迷总教练」。
                用户正在尝试打开娱乐/短视频应用（如抖音/小红书/快手/B站），并提交了进入理由。
                你的职责：严格审查该理由，拒绝一切敷衍、无意义或纯娱乐消遣的借口，只允许具有明确、具体、正当学习/工作/实际生活需求的行为进入！

                审核规则：
                1. 【坚决打回 (REJECT)】：
                   - 敷衍模糊词汇（例如："随便看看", "无聊", "刷一下", "看视频", "摸鱼", "消遣", "玩一下", "打发时间" 等）。
                   - 纯娱乐沉迷借口（例如："看美女", "看帅哥", "看搞笑段子", "看短剧", "吃瓜八卦", "刷小姐姐" 等）。
                   - 无具体事项的空话（例如："学习", "查资料", "看看教程" 但未说明具体学什么）。
                2. 【予以批准 (APPROVE)】：
                   - 具有明确具体的任务、学习、工作或生活技能目的。例如："学习口播文案拆解与录制技巧"、"查找西红柿炒蛋食谱步骤"、"查看客户在后台的私信留言"、"查找考研英语真题讲解" 等。
                3. 输出格式必须严格为以下两种之一，绝不要有多余内容：
                   REJECT|打回原因（指出其借口的消遣本质，给出犀利自律警告，15-30字）
                   APPROVE|鼓励语（简洁有力的专注激励，10-20字）
            """.trimIndent()

            val userMessage = "应用：$appName\n用户提交的进入理由：$trimmed"

            val bodyJson = JsonObject().apply {
                addProperty("model", PrefManager.modelName)
                val messages = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userMessage)
                    })
                }
                add("messages", messages)
                addProperty("temperature", 0.1)
                addProperty("max_tokens", 60)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonObj = gson.fromJson(respStr, JsonObject::class.java)
                val reply = jsonObj.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString?.trim() ?: ""

                if (reply.startsWith("REJECT", ignoreCase = true)) {
                    val reason = reply.substringAfter("|", "理由过于敷衍或纯属消遣娱乐，已被 AI 自律教练打回！").trim()
                    return@withContext AiAuditResult(false, reason)
                } else if (reply.startsWith("APPROVE", ignoreCase = true)) {
                    val praise = reply.substringAfter("|", "目标明确，专注高效！").trim()
                    return@withContext AiAuditResult(true, praise)
                }
            }
        } catch (e: Exception) {
            Log.e("StopScroll", "AI audit failed: ${e.message}")
        }

        // 网络异常时回退到本地智能离线审核
        return@withContext localOfflineSmartAudit(trimmed)
    }

    /**
     * 🛡️ AI 审核用户解除监控或关闭宵禁的理由（防止深夜冲动破戒）
     */
    suspend fun auditUnlockReason(actionType: String, reason: String): AiAuditResult = withContext(Dispatchers.IO) {
        val trimmed = reason.trim()

        if (trimmed.length < 5) {
            return@withContext AiAuditResult(false, "理由过于简短敷衍！为了防止冲动破戒，请诚实阐述具体紧急事由。")
        }

        val slackingKeywords = listOf("无聊", "随便", "想玩", "想看", "关一下", "解除", "烦", "手滑", "摸鱼", "消遣", "睡不着", "就看会")
        if (slackingKeywords.any { trimmed.contains(it) }) {
            return@withContext AiAuditResult(false, "检测到娱乐冲动与破戒倾向！自律需要坚持，拒绝解除监控保护！")
        }

        val apiKey = PrefManager.apiKey.trim()
        if (apiKey.isBlank()) {
            // 本地离线判断：只有明确说明工作/业务/紧急情况才放行
            val workKeywords = listOf("工作", "业务", "客户", "紧急", "公司", "维护", "直播", "测试", "发布")
            return@withContext if (workKeywords.any { trimmed.contains(it) }) {
                AiAuditResult(true, "紧急工作事由已确认，请尽快处理，勿浏览无关内容！")
            } else {
                AiAuditResult(false, "理由未包含明确的紧急工作或必要事由，AI 守门员已驳回解除请求！")
            }
        }

        try {
            val url = cleanUrl(PrefManager.baseUrl) + "/chat/completions"
            val systemPrompt = """
                你是一个极度严格的自律防破戒安全守门员。
                用户正在试图解除手机自律防沉迷监控（例如解除抖音监控、关闭夜间宵禁）。
                你的职责是：识破用户一时的娱乐冲动与成瘾借口，严防破戒！
                规则：
                1. 若理由是“想看视频”、“无聊”、“不想被管”、“烦”、“手滑”、“摸鱼”、“随便关一下”或任何非生产力理由，必须坚决打回（REJECT）！
                2. 只有在确实具备紧急、重大的正当工作/学习/业务事由（例如“公司紧急业务官方号直播排障”、“必须处理客户紧急订单”）时才可准许（APPROVE）。
                输出格式严格为：
                REJECT|打回原因（坚决提醒用户保持初心，严防深夜破戒）
                APPROVE|准许原因
            """.trimIndent()

            val bodyJson = JsonObject().apply {
                addProperty("model", PrefManager.modelName)
                val messages = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "操作：$actionType\n用户理由：$trimmed")
                    })
                }
                add("messages", messages)
                addProperty("temperature", 0.1)
                addProperty("max_tokens", 60)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonObj = gson.fromJson(respStr, JsonObject::class.java)
                val reply = jsonObj.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString?.trim() ?: ""

                if (reply.startsWith("REJECT", ignoreCase = true)) {
                    val reasonText = reply.substringAfter("|", "检测到破戒冲动，请求已被驳回！保持自律！").trim()
                    return@withContext AiAuditResult(false, reasonText)
                } else if (reply.startsWith("APPROVE", ignoreCase = true)) {
                    return@withContext AiAuditResult(true, "紧急需求已批准，请务必保持专注。")
                }
            }
        } catch (e: Exception) {
            Log.e("StopScroll", "AI unlock audit failed: ${e.message}")
        }

        return@withContext AiAuditResult(false, "未能确认必要正当性，已维持自律监控开启状态！")
    }

    /**
     * 屏幕内容低功耗检测：带 Hash 去重与本地快速过滤，彻底杜绝发热
     */
    suspend fun checkContentRelevance(userGoal: String, screenContent: String): AiCheckResult = withContext(Dispatchers.IO) {
        val apiKey = PrefManager.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext AiCheckResult(false, "未配置 API Key，跳过检测")
        }

        // 1. 屏幕内容 Hash 差分去重：若屏幕文字未变（如暂停、看同一图文），零请求零功耗
        val currentHash = screenContent.hashCode()
        if (currentHash == lastScreenHash) {
            return@withContext AiCheckResult(false, "屏幕内容未变，跳过网络调用")
        }
        lastScreenHash = currentHash

        // 2. 本地目标相关词初筛（如果屏幕上已包含目标核心词汇，本地直接放行，减少网络与发热）
        val goalKeywords = userGoal.split(" ", "，", ",", "的", "和", "看", "学").filter { it.length >= 2 }
        if (goalKeywords.isNotEmpty() && goalKeywords.any { screenContent.contains(it) }) {
            return@withContext AiCheckResult(false, "本地命中目标关键词，判定相关")
        }

        try {
            val url = cleanUrl(PrefManager.baseUrl) + "/chat/completions"
            val systemPrompt = """
                你是一个严格的手机自律与防沉迷监督AI。用户在使用应用前明确声明了自己的目标。
                现在给你当前手机屏幕上提取的文本内容。
                请判断用户当前浏览或操作的内容是否严重偏离了其预定目标。
                规则：
                1. 搜索框操作、寻找相关教程、评论区讨论教程属于正常行为，回答 NO。
                2. 只有当用户正在浏览明显无关的纯娱乐、八卦、搞笑、无意义短视频、美女帅哥热舞等，才算严重偏离。
                3. 输出格式必须严格为：YES|偏离原因 或者是 NO。例如：YES|当前正在刷明星八卦视频。
            """.trimIndent()

            val userMessage = """
                用户声明的目标: $userGoal
                当前屏幕文本提取采样:
                ${screenContent.take(350)}
            """.trimIndent()

            val bodyJson = JsonObject().apply {
                addProperty("model", PrefManager.modelName)
                val messages = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userMessage)
                    })
                }
                add("messages", messages)
                addProperty("temperature", 0.1)
                addProperty("max_tokens", 40)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonObj = gson.fromJson(respStr, JsonObject::class.java)
                val reply = jsonObj.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString?.trim() ?: "NO"

                if (reply.startsWith("YES", ignoreCase = true)) {
                    val reason = reply.substringAfter("|", "偏离目标内容").trim()
                    return@withContext AiCheckResult(true, reason)
                }
            }
        } catch (e: Exception) {
            Log.e("StopScroll", "Content relevance check error: ${e.message}")
        }

        return@withContext AiCheckResult(false, "")
    }

    private fun localCheckGoal(goal: String): AiAuditResult? {
        if (goal.length < 3) {
            return AiAuditResult(false, "🚫 打回：理由太简短敷衍！切勿随意输入单字，请写明具体学习或工作事项。")
        }

        // 纯无意义数字、英文字母连打或标点符号
        if (goal.matches(Regex("^[0-9a-zA-Z\\s\\p{P}]+$")) && goal.length < 8) {
            return AiAuditResult(false, "🚫 打回：检测到无意义字符/字母乱码！请输入真实具体的中文事由。")
        }

        // 常见敷衍高危词库
        val slackingWords = listOf(
            "随便", "无聊", "刷刷", "看看", "玩会", "玩一下", "摸鱼", "消遣",
            "看美女", "看帅哥", "热舞", "段子", "短剧", "吃瓜", "八卦", "无聊刷",
            "打发时间", "睡不着", "就看会", "放松一下"
        )
        for (w in slackingWords) {
            if (goal.contains(w)) {
                return AiAuditResult(false, "🚫 打回：检测到消遣娱乐借口（含「$w」）！当前应用已被监控，禁止无意义沉迷，请放下手机。")
            }
        }

        return null
    }

    private fun localOfflineSmartAudit(goal: String): AiAuditResult {
        val check = localCheckGoal(goal)
        if (check != null) return check

        if (goal.length < 4) {
            return AiAuditResult(false, "🚫 打回：目标事由不够具体（不少于4个字），例如「学习口播文案剪辑」或「查找西红柿炒蛋做法」。")
        }

        return AiAuditResult(true, "🎯 目标已确认，请保持专注，在设定时间内完成！")
    }

    private fun cleanUrl(url: String): String {
        var u = url.trim()
        if (u.endsWith("/")) u = u.dropLast(1)
        return u
    }
}
