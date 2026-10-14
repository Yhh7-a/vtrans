package com.vtuber.vtransoverlay

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

data class SubtitleResult(
    val originalText: String,
    val translatedText: String
)

class TranslationEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 保存最近 4 句上下文，解决直播口语断句跨片段导致的语义断裂问题
    private val contextHistory = ArrayDeque<String>(4)

    // 常见静音/BGM 幻觉黑名单过滤
    private val bgmHallucinations = listOf(
        "ご視聴ありがとうございました",
        "チャンネル登録",
        "字幕作成",
        "Thank you for watching",
        "Thanks for watching",
        "Subtitles by",
        "Amara.org",
        "MBC뉴스",
        "请不吝点赞"
    )

    private val senseVoiceTagRegex = Regex("<\\|[^|]+\\|>")

    fun clearContext() {
        synchronized(contextHistory) {
            contextHistory.clear()
        }
    }

    private fun getContextString(): String {
        synchronized(contextHistory) {
            if (contextHistory.isEmpty()) return "（暂无前文）"
            return contextHistory.joinToString(" | ")
        }
    }

    private fun pushContext(entry: String) {
        synchronized(contextHistory) {
            if (contextHistory.size >= 4) {
                contextHistory.removeFirst()
            }
            contextHistory.addLast(entry)
        }
    }

    private fun buildLanguageInstruction(sourceLang: String): String {
        return when (sourceLang) {
            "ja" -> "当前直播主要语言为【日语 (Japanese)】，部分词汇可能夹杂英语。"
            "en" -> "当前直播主要语言为【英语 (English)】，可能夹杂少量日语二次元词汇（如 kawaii, senpai, teetee）。"
            else -> "当前直播可能为【日语或英语（或日英混杂）】，请自动识别语种。"
        }
    }

    suspend fun processAudioChunk(wavBytes: ByteArray, settings: AppSettings): SubtitleResult? =
        withContext(Dispatchers.IO) {
            try {
                when (settings.engineType) {
                    EngineType.CN_DIRECT_SENSEVOICE -> translateWithAsrAndLlm(
                        wavBytes = wavBytes,
                        asrApiKey = settings.cnApiKey,
                        asrBaseUrl = settings.cnBaseUrl,
                        asrModel = settings.cnAsrModel,
                        llmApiKey = settings.cnApiKey,
                        llmBaseUrl = settings.cnBaseUrl,
                        llmModel = settings.cnLlmModel,
                        sourceLang = settings.sourceLang,
                        glossary = settings.customGlossary
                    )
                    EngineType.GEMINI_AUDIO -> translateWithGeminiAudio(wavBytes, settings)
                    EngineType.CUSTOM_WHISPER_LLM -> translateWithAsrAndLlm(
                        wavBytes = wavBytes,
                        asrApiKey = settings.whisperApiKey,
                        asrBaseUrl = settings.whisperBaseUrl,
                        asrModel = settings.whisperModel,
                        llmApiKey = settings.llmApiKey,
                        llmBaseUrl = settings.llmBaseUrl,
                        llmModel = settings.llmModel,
                        sourceLang = settings.sourceLang,
                        glossary = settings.customGlossary
                    )
                }
            } catch (e: Exception) {
                SubtitleResult(
                    originalText = "网络或接口异常",
                    translatedText = "请检查网络或 API Key 配置: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }

    /**
     * 国内直连免翻墙模式（阿里 SenseVoiceSmall + DeepSeek-V3）及通用 Whisper+LLM 模式
     */
    private fun translateWithAsrAndLlm(
        wavBytes: ByteArray,
        asrApiKey: String,
        asrBaseUrl: String,
        asrModel: String,
        llmApiKey: String,
        llmBaseUrl: String,
        llmModel: String,
        sourceLang: String,
        glossary: String
    ): SubtitleResult? {
        if (asrApiKey.isBlank() || llmApiKey.isBlank()) {
            return SubtitleResult("未配置 API Key", "请先在主界面填写 API Key（国内直连推荐硅基流动 Key）")
        }

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", asrModel)
            .addFormDataPart("response_format", "json")

        if ((sourceLang == "ja" || sourceLang == "en") && !asrModel.contains("SenseVoice", ignoreCase = true)) {
            multipartBuilder.addFormDataPart("language", sourceLang)
        }

        val asrReq = Request.Builder()
            .url("$asrBaseUrl/audio/transcriptions")
            .addHeader("Authorization", "Bearer $asrApiKey")
            .post(multipartBuilder.build())
            .build()

        val rawTranscript = httpClient.newCall(asrReq).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return SubtitleResult("语音识别 HTTP ${resp.code}", "请检查 API Key 是否有效或模型名称是否正确")
            }
            JSONObject(bodyStr).optString("text", "").trim()
        }

        // 清理 SenseVoice 可能附带的 <|ja|><|HAPPY|> 等控制标签
        val transcript = rawTranscript.replace(senseVoiceTagRegex, "").trim()

        if (transcript.length < 2) return null
        if (bgmHallucinations.any { transcript.contains(it, ignoreCase = true) }) {
            return null
        }

        // 第二步：调用 DeepSeek / Qwen 结合 VTuber 术语库与上下文翻译为地道中文
        val recentContext = getContextString()
        val systemPrompt = """
            你是一位专业的日本与欧美 VTuber（虚拟主播）直播同声传译员（B站资深同传man）。
            请将发来的直播语音识别文本翻译成地道、自然、简练的简体中文同传字幕。
            - 自动修正语音识别中的日语/英语同音错字。
            - 结合【最近前文】使口语断句语义连贯，保留主播的语气。
            - 严格参考以下【VTuber 专属术语表】：
            $glossary
            - 只输出最终中文译文，不要带任何解释或引号。如果原文完全是无意义乱码或纯语气词，只输出 [EMPTY]。
        """.trimIndent()

        val userPrompt = "【最近前文】：$recentContext\n【当前原句】：$transcript"

        val chatJson = JSONObject().apply {
            put("model", llmModel)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val llmReq = Request.Builder()
            .url("$llmBaseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $llmApiKey")
            .post(chatJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(llmReq).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return SubtitleResult(transcript, "[翻译请求失败 HTTP ${resp.code}]")
            }
            val zh = JSONObject(bodyStr)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim() ?: ""

            if (zh.isEmpty() || zh.contains("[EMPTY]")) return null
            pushContext("$transcript => $zh")
            return SubtitleResult(transcript, zh)
        }
    }

    /**
     * 海外模式：使用 Gemini 2.5 Flash 原生多模态音频直译（需海外网络环境）
     */
    private fun translateWithGeminiAudio(wavBytes: ByteArray, settings: AppSettings): SubtitleResult? {
        if (settings.geminiApiKey.isBlank()) {
            return SubtitleResult("未配置 Gemini API Key", "请先在主界面填写 Gemini API Key")
        }

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val langHint = buildLanguageInstruction(settings.sourceLang)
        val recentContext = getContextString()

        val systemPrompt = """
            你是一位精通日本与欧美 VTuber（虚拟主播）文化的资深同声传译员。
            $langHint
            请直接听取这段直播音频片段，并将其翻译为自然、生动、符合中文二次元/直播间观众习惯的简体中文同传字幕。
            
            【同传核心规则】：
            1. 如果音频中只有背景音乐(BGM)、游戏音效、纯呼吸声或无法辨认的杂音，请直接返回 {"orig": "", "zh": ""}，绝不要编造台词。
            2. 结合【最近前文】补全因切片中断的半句话，使中文译文流畅自然，保留主播的情绪（如惊讶、吐槽、撒娇）。
            3. 严格参考以下【VTuber 专属术语表】进行地道翻译：
            ${settings.customGlossary}
            
            【最近前文】：
            $recentContext
            
            请严格且仅输出如下 JSON 格式（不要包含 markdown 代码块标记）：
            {"orig": "识别出的日文或英文原句", "zh": "地道简练的中文同传译文"}
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "audio/wav")
                                put("data", base64Audio)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.geminiModel}:generateContent?key=${settings.geminiApiKey}"
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        httpClient.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return SubtitleResult("Gemini HTTP ${response.code}", "调用失败，国内网络不翻墙请切换为【方案 A：国内直连模式】")
            }
            val root = JSONObject(respBody)
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim() ?: return null

            val cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed = JSONObject(cleanJson)
            val orig = parsed.optString("orig", "").trim()
            val zh = parsed.optString("zh", "").trim()

            if (zh.isEmpty() || zh == "[EMPTY]") return null
            pushContext("$orig => $zh")
            return SubtitleResult(orig, zh)
        }
    }
}
