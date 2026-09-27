package com.shawn.stopscroll.ai

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

object AiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
     * 判断屏幕内容是否严重偏离用户预定目标
     */
    suspend fun checkContentRelevance(userGoal: String, screenContent: String): AiCheckResult = withContext(Dispatchers.IO) {
        val apiKey = PrefManager.apiKey
        if (apiKey.isBlank()) {
            return@withContext AiCheckResult(false, "未配置 API Key，跳过检测")
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
                ${screenContent.take(500)}
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
                addProperty("max_tokens", 50)
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
            e.printStackTrace()
        }

        return@withContext AiCheckResult(false, "")
    }

    private fun cleanUrl(url: String): String {
        var u = url.trim()
        if (u.endsWith("/")) u = u.dropLast(1)
        return u
    }
}
