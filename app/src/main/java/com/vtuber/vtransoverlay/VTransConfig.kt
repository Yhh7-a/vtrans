package com.vtuber.vtransoverlay

import android.content.Context
import android.content.SharedPreferences

enum class EngineType {
    CN_DIRECT_SENSEVOICE, // 国内直连免翻墙（硅基流动 SenseVoiceSmall 语音识别 + DeepSeek-V3/Qwen 翻译，单 Key 搞定，看B站不卡）
    GEMINI_AUDIO,         // 海外直译（Gemini 2.5 Flash 原生音频一步同传，适合开梯子看 YouTube/Twitch 时使用）
    CUSTOM_WHISPER_LLM    // 自定义双引擎（Groq/OpenAI Whisper + 任意 OpenAI 兼容大模型）
}

enum class AudioSourceMode {
    INTERNAL_AUDIO, // Android 10+ AudioPlaybackCapture 系统内录（直接抓取 Bilibili/YouTube/Twitch 内部声音）
    MICROPHONE      // 麦克风拾音（备用方案）
}

data class AppSettings(
    val engineType: EngineType = EngineType.CN_DIRECT_SENSEVOICE,
    val cnApiKey: String = "",
    val cnBaseUrl: String = "https://api.siliconflow.cn/v1",
    val cnAsrModel: String = "FunAudioLLM/SenseVoiceSmall",
    val cnLlmModel: String = "deepseek-ai/DeepSeek-V3",
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val whisperApiKey: String = "",
    val whisperBaseUrl: String = "https://api.groq.com/openai/v1",
    val whisperModel: String = "whisper-large-v3-turbo",
    val llmApiKey: String = "",
    val llmBaseUrl: String = "https://api.deepseek.com/v1",
    val llmModel: String = "deepseek-chat",
    val sourceLang: String = "auto", // "ja", "en", "auto"
    val audioSourceMode: AudioSourceMode = AudioSourceMode.INTERNAL_AUDIO,
    val chunkSeconds: Float = 3.5f,
    val vadSilenceThreshold: Int = 280,
    val fontSizeSp: Float = 18f,
    val bgOpacityPercent: Int = 78,
    val showOriginalText: Boolean = true,
    val customGlossary: String = DEFAULT_VTUBER_GLOSSARY
)

object VTransConfig {
    private const val PREF_NAME = "vtrans_overlay_prefs"

    val DEFAULT_VTUBER_GLOSSARY = """
        てぇてぇ -> 贴贴 (形容关系极好、互动很有爱)
        スパチャ / Superchat / SC -> SC / 醒目留言
        メンバーシップ / Membership -> 舰长 / 会员 (B站语境可译为上舰/会员)
        ぽん / Pon / ポンコツ -> 犯傻 / 笨拙可爱 (天然呆操作)
        助かる / Tasukaru -> 活了 / 太棒了 / 感谢款待
        限界化 / 限界オタク -> 发病 / 破防 / 激动到语无伦次
        ガチ恋 / Gachikoi -> 真爱粉 / 恋爱粉
        草 / kusa / www -> 草 / 哈哈哈
        初見 / Shoken -> 初见 / 新来的观众
        切り抜き / Kirinuki / Clip -> 切片 / 烤肉熟肉
        コラボ / Collab -> 联动
        枠 / Waku -> 直播间 / 档
        歌枠 / Utawaku -> 歌回
        雑談 / Zatsudan -> 杂谈
        おつ / Otsukare -> 辛苦啦 / 晚安 (下播问候)
        待機 / Taiki -> 待机
        回線落ち -> 掉线 / 网卡了
        Pog / Poggers -> 太强了 / 牛逼
        Chat -> 弹幕 / 观众们
        Based -> 太真实了 / 有品
        LMAO / LMFAO -> 笑死
        GG / GGs -> 打得好 / 精彩
        GOAT -> 永远的神
    """.trimIndent()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val engineStr = p.getString("engineType", EngineType.CN_DIRECT_SENSEVOICE.name) ?: EngineType.CN_DIRECT_SENSEVOICE.name
        val audioStr = p.getString("audioSourceMode", AudioSourceMode.INTERNAL_AUDIO.name) ?: AudioSourceMode.INTERNAL_AUDIO.name
        return AppSettings(
            engineType = runCatching { EngineType.valueOf(engineStr) }.getOrDefault(EngineType.CN_DIRECT_SENSEVOICE),
            cnApiKey = p.getString("cnApiKey", "") ?: "",
            cnBaseUrl = p.getString("cnBaseUrl", "https://api.siliconflow.cn/v1") ?: "https://api.siliconflow.cn/v1",
            cnAsrModel = p.getString("cnAsrModel", "FunAudioLLM/SenseVoiceSmall") ?: "FunAudioLLM/SenseVoiceSmall",
            cnLlmModel = p.getString("cnLlmModel", "deepseek-ai/DeepSeek-V3") ?: "deepseek-ai/DeepSeek-V3",
            geminiApiKey = p.getString("geminiApiKey", "") ?: "",
            geminiModel = p.getString("geminiModel", "gemini-2.5-flash") ?: "gemini-2.5-flash",
            whisperApiKey = p.getString("whisperApiKey", "") ?: "",
            whisperBaseUrl = p.getString("whisperBaseUrl", "https://api.groq.com/openai/v1") ?: "https://api.groq.com/openai/v1",
            whisperModel = p.getString("whisperModel", "whisper-large-v3-turbo") ?: "whisper-large-v3-turbo",
            llmApiKey = p.getString("llmApiKey", "") ?: "",
            llmBaseUrl = p.getString("llmBaseUrl", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
            llmModel = p.getString("llmModel", "deepseek-chat") ?: "deepseek-chat",
            sourceLang = p.getString("sourceLang", "auto") ?: "auto",
            audioSourceMode = runCatching { AudioSourceMode.valueOf(audioStr) }.getOrDefault(AudioSourceMode.INTERNAL_AUDIO),
            chunkSeconds = p.getFloat("chunkSeconds", 3.5f),
            vadSilenceThreshold = p.getInt("vadSilenceThreshold", 280),
            fontSizeSp = p.getFloat("fontSizeSp", 18f),
            bgOpacityPercent = p.getInt("bgOpacityPercent", 78),
            showOriginalText = p.getBoolean("showOriginalText", true),
            customGlossary = p.getString("customGlossary", DEFAULT_VTUBER_GLOSSARY) ?: DEFAULT_VTUBER_GLOSSARY
        )
    }

    fun save(context: Context, s: AppSettings) {
        prefs(context).edit().apply {
            putString("engineType", s.engineType.name)
            putString("cnApiKey", s.cnApiKey.trim())
            putString("cnBaseUrl", s.cnBaseUrl.trim().trimEnd('/'))
            putString("cnAsrModel", s.cnAsrModel.trim())
            putString("cnLlmModel", s.cnLlmModel.trim())
            putString("geminiApiKey", s.geminiApiKey.trim())
            putString("geminiModel", s.geminiModel.trim())
            putString("whisperApiKey", s.whisperApiKey.trim())
            putString("whisperBaseUrl", s.whisperBaseUrl.trim().trimEnd('/'))
            putString("whisperModel", s.whisperModel.trim())
            putString("llmApiKey", s.llmApiKey.trim())
            putString("llmBaseUrl", s.llmBaseUrl.trim().trimEnd('/'))
            putString("llmModel", s.llmModel.trim())
            putString("sourceLang", s.sourceLang)
            putString("audioSourceMode", s.audioSourceMode.name)
            putFloat("chunkSeconds", s.chunkSeconds)
            putInt("vadSilenceThreshold", s.vadSilenceThreshold)
            putFloat("fontSizeSp", s.fontSizeSp)
            putInt("bgOpacityPercent", s.bgOpacityPercent)
            putBoolean("showOriginalText", s.showOriginalText)
            putString("customGlossary", s.customGlossary)
            apply()
        }
    }
}
