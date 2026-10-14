package com.vtuber.vtransoverlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionStatus: TextView
    private lateinit var spinnerEngine: Spinner
    private lateinit var spinnerLang: Spinner
    private lateinit var spinnerAudioSource: Spinner

    private lateinit var cnDirectConfigBox: LinearLayout
    private lateinit var etCnKey: EditText
    private lateinit var etCnBaseUrl: EditText
    private lateinit var etCnAsrModel: EditText
    private lateinit var etCnLlmModel: EditText

    private lateinit var geminiConfigBox: LinearLayout
    private lateinit var etGeminiKey: EditText
    private lateinit var etGeminiModel: EditText

    private lateinit var whisperLlmConfigBox: LinearLayout
    private lateinit var etWhisperKey: EditText
    private lateinit var etWhisperUrl: EditText
    private lateinit var etWhisperModel: EditText
    private lateinit var etLlmKey: EditText
    private lateinit var etLlmUrl: EditText
    private lateinit var etLlmModel: EditText

    private lateinit var seekFontSize: SeekBar
    private lateinit var seekOpacity: SeekBar
    private lateinit var cbShowOrig: CheckBox
    private lateinit var etGlossary: EditText

    private val requestAudioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionBanner()
        }

    private val requestMediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startTranslatorService(result.resultCode, result.data)
            } else {
                Toast.makeText(this, "未授予系统内录权限，你也可以切换为麦克风模式启动", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildProgrammaticUi()
        loadSettingsIntoUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
    }

    private fun buildProgrammaticUi() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }

        val title = TextView(this).apply {
            text = "VTrans VTuber 同传悬浮窗"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "支持 Bilibili 国内直连免翻墙 & YouTube/Twitch 海外看播｜系统内录 + V圈词库 + 点击穿透"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(16))
        }
        container.addView(title)
        container.addView(subtitle)

        fun makeCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        fun makeSectionHeader(label: String): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }

        fun makeFieldLabel(label: String): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(4))
        }

        fun makeInput(hintText: String, isPassword: Boolean = false): EditText = EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
            }
            if (isPassword) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
            }
        }

        // 卡片 1：权限状态与快捷授权
        val permCard = makeCard()
        permCard.addView(makeSectionHeader("1. 必备权限检查（首次使用请点击授权）"))
        tvPermissionStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(10))
        }
        permCard.addView(tvPermissionStatus)

        val permBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnGrantOverlay = Button(this).apply {
            text = "开启悬浮窗权限"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnGrantAudio = Button(this).apply {
            text = "授予录音权限"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                requestAudioPermLauncher.launch(perms.toTypedArray())
            }
        }
        permBtnRow.addView(btnGrantOverlay)
        permBtnRow.addView(btnGrantAudio)
        permCard.addView(permBtnRow)
        container.addView(permCard)

        // 卡片 2：直播语种与音源模式
        val sourceCard = makeCard()
        sourceCard.addView(makeSectionHeader("2. 直播语种与声音抓取方式"))

        sourceCard.addView(makeFieldLabel("主播语言（翻译为简体中文）："))
        spinnerLang = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("日英混合 / 自动识别 (推荐)", "日语 VTuber (Japanese)", "英语 VTuber (English)")
            )
        }
        sourceCard.addView(spinnerLang)

        sourceCard.addView(makeFieldLabel("音频采集方式："))
        spinnerAudioSource = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("系统内部声音直录 (推荐，戴耳机看B站/YouTube也能同传)", "手机麦克风拾音 (外放备用)")
            )
        }
        sourceCard.addView(spinnerAudioSource)
        container.addView(sourceCard)

        // 卡片 3：AI 同传引擎配置（支持国内免翻墙直连 & 海外模式）
        val engineCard = makeCard()
        engineCard.addView(makeSectionHeader("3. 同传大模型引擎（支持国内免翻墙直连）"))
        engineCard.addView(makeFieldLabel("选择网络与引擎方案："))
        spinnerEngine = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "方案 A【国内直连免翻墙】：硅基流动 SenseVoice + DeepSeek (看B站首选)",
                    "方案 B【海外模式需梯子】：Gemini 2.5 Flash 原生音频一步同传",
                    "方案 C【自定义双引擎】：Groq/OpenAI Whisper + 自定义大模型"
                )
            )
        }
        engineCard.addView(spinnerEngine)

        // 方案 A：国内直连配置框
        cnDirectConfigBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        cnDirectConfigBox.addView(makeFieldLabel("硅基流动 API Key (国内访问 cloud.siliconflow.cn 免费注册获取，单 Key 搞定语音+翻译)："))
        etCnKey = makeInput("粘贴 sk-... 开头的硅基流动密钥", isPassword = true)
        cnDirectConfigBox.addView(etCnKey)
        cnDirectConfigBox.addView(makeFieldLabel("国内接口地址 (默认无需修改，直连免翻墙)："))
        etCnBaseUrl = makeInput("https://api.siliconflow.cn/v1")
        cnDirectConfigBox.addView(etCnBaseUrl)
        cnDirectConfigBox.addView(makeFieldLabel("语音识别模型 (阿里 SenseVoiceSmall 免费且对日英V圈极准)："))
        etCnAsrModel = makeInput("FunAudioLLM/SenseVoiceSmall")
        cnDirectConfigBox.addView(etCnAsrModel)
        cnDirectConfigBox.addView(makeFieldLabel("同传翻译大模型 (DeepSeek-V3 或 Qwen/Qwen2.5-72B-Instruct)："))
        etCnLlmModel = makeInput("deepseek-ai/DeepSeek-V3")
        cnDirectConfigBox.addView(etCnLlmModel)
        engineCard.addView(cnDirectConfigBox)

        // 方案 B：Gemini 配置框
        geminiConfigBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        geminiConfigBox.addView(makeFieldLabel("Gemini API Key (需开启代理才可访问 Google 接口)："))
        etGeminiKey = makeInput("粘贴 AIzaSy... 开头的 API Key", isPassword = true)
        geminiConfigBox.addView(etGeminiKey)
        geminiConfigBox.addView(makeFieldLabel("Gemini 模型名称："))
        etGeminiModel = makeInput("gemini-2.5-flash")
        geminiConfigBox.addView(etGeminiModel)
        engineCard.addView(geminiConfigBox)

        // 方案 C：自定义 Whisper + LLM 配置框
        whisperLlmConfigBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        whisperLlmConfigBox.addView(makeFieldLabel("Whisper API Key："))
        etWhisperKey = makeInput("gsk_... 或 sk-...", isPassword = true)
        whisperLlmConfigBox.addView(etWhisperKey)
        whisperLlmConfigBox.addView(makeFieldLabel("Whisper 接口地址 (Base URL)："))
        etWhisperUrl = makeInput("https://api.groq.com/openai/v1")
        whisperLlmConfigBox.addView(etWhisperUrl)
        whisperLlmConfigBox.addView(makeFieldLabel("Whisper 模型名称："))
        etWhisperModel = makeInput("whisper-large-v3-turbo")
        whisperLlmConfigBox.addView(etWhisperModel)

        whisperLlmConfigBox.addView(makeFieldLabel("翻译大模型 API Key (如 DeepSeek 官方 Key)："))
        etLlmKey = makeInput("sk-...", isPassword = true)
        whisperLlmConfigBox.addView(etLlmKey)
        whisperLlmConfigBox.addView(makeFieldLabel("翻译大模型接口地址 (Base URL)："))
        etLlmUrl = makeInput("https://api.deepseek.com/v1")
        whisperLlmConfigBox.addView(etLlmUrl)
        whisperLlmConfigBox.addView(makeFieldLabel("翻译大模型名称："))
        etLlmModel = makeInput("deepseek-chat")
        whisperLlmConfigBox.addView(etLlmModel)
        engineCard.addView(whisperLlmConfigBox)

        spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cnDirectConfigBox.visibility = if (position == 0) View.VISIBLE else View.GONE
                geminiConfigBox.visibility = if (position == 1) View.VISIBLE else View.GONE
                whisperLlmConfigBox.visibility = if (position == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        container.addView(engineCard)

        // 卡片 4：悬浮窗外观与 VTuber 专属词库
        val styleCard = makeCard()
        styleCard.addView(makeSectionHeader("4. 悬浮窗外观与 VTuber 专属黑话词库"))

        val tvFontLabel = makeFieldLabel("中文字幕字号大小 (18sp)：")
        styleCard.addView(tvFontLabel)
        seekFontSize = SeekBar(this).apply {
            max = 16
            progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvFontLabel.text = "中文字幕字号大小 (${14 + progress}sp)："
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    saveUiSettings()
                    notifyStyleChanged()
                }
            })
        }
        styleCard.addView(seekFontSize)

        val tvOpacityLabel = makeFieldLabel("悬浮窗背景不透明度 (78%)：")
        styleCard.addView(tvOpacityLabel)
        seekOpacity = SeekBar(this).apply {
            max = 75
            progress = 58
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvOpacityLabel.text = "悬浮窗背景不透明度 (${20 + progress}%)："
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    saveUiSettings()
                    notifyStyleChanged()
                }
            })
        }
        styleCard.addView(seekOpacity)

        cbShowOrig = CheckBox(this).apply {
            text = "同时显示识别出的日/英原文（双语对照字幕）"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, _ ->
                saveUiSettings()
                notifyStyleChanged()
            }
        }
        styleCard.addView(cbShowOrig)

        styleCard.addView(makeFieldLabel("VTuber 专属术语表（可随时添加你推的名字、粉丝名与口癖）："))
        etGlossary = EditText(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 6
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
            }
        }
        styleCard.addView(etGlossary)
        container.addView(styleCard)

        val btnStart = Button(this).apply {
            text = "🚀 保存配置并启动同传悬浮窗"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#2563EB"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                onStartOverlayClicked()
            }
        }
        container.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "关闭悬浮窗服务"
            setTextColor(Color.parseColor("#FCA5A5"))
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, FloatingTranslatorService::class.java).apply {
                    action = FloatingTranslatorService.ACTION_STOP
                }
                startService(stopIntent)
            }
        }
        container.addView(btnStop)

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun refreshPermissionBanner() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        tvPermissionStatus.text = buildString {
            append(if (hasOverlay) "✅ 悬浮窗权限：已开启
" else "❌ 悬浮窗权限：未开启（请点击下方按钮开启）
")
            append(if (hasAudio) "✅ 录音/内录权限：已授权" else "❌ 录音/内录权限：未授权（请点击下方按钮授权）")
        }
        tvPermissionStatus.setTextColor(
            if (hasOverlay && hasAudio) Color.parseColor("#4ADE80") else Color.parseColor("#FDE047")
        )
    }

    private fun loadSettingsIntoUi() {
        val s = VTransConfig.load(this)
        spinnerEngine.setSelection(
            when (s.engineType) {
                EngineType.CN_DIRECT_SENSEVOICE -> 0
                EngineType.GEMINI_AUDIO -> 1
                EngineType.CUSTOM_WHISPER_LLM -> 2
            }
        )
        spinnerLang.setSelection(
            when (s.sourceLang) {
                "ja" -> 1
                "en" -> 2
                else -> 0
            }
        )
        spinnerAudioSource.setSelection(if (s.audioSourceMode == AudioSourceMode.INTERNAL_AUDIO) 0 else 1)

        etCnKey.setText(s.cnApiKey)
        etCnBaseUrl.setText(s.cnBaseUrl)
        etCnAsrModel.setText(s.cnAsrModel)
        etCnLlmModel.setText(s.cnLlmModel)

        etGeminiKey.setText(s.geminiApiKey)
        etGeminiModel.setText(s.geminiModel)
        etWhisperKey.setText(s.whisperApiKey)
        etWhisperUrl.setText(s.whisperBaseUrl)
        etWhisperModel.setText(s.whisperModel)
        etLlmKey.setText(s.llmApiKey)
        etLlmUrl.setText(s.llmBaseUrl)
        etLlmModel.setText(s.llmModel)

        seekFontSize.progress = (s.fontSizeSp.toInt() - 14).coerceIn(0, 16)
        seekOpacity.progress = (s.bgOpacityPercent - 20).coerceIn(0, 75)
        cbShowOrig.isChecked = s.showOriginalText
        etGlossary.setText(s.customGlossary)
    }

    private fun saveUiSettings(): AppSettings {
        val langCode = when (spinnerLang.selectedItemPosition) {
            1 -> "ja"
            2 -> "en"
            else -> "auto"
        }
        val engine = when (spinnerEngine.selectedItemPosition) {
            0 -> EngineType.CN_DIRECT_SENSEVOICE
            1 -> EngineType.GEMINI_AUDIO
            else -> EngineType.CUSTOM_WHISPER_LLM
        }
        val settings = AppSettings(
            engineType = engine,
            cnApiKey = etCnKey.text.toString(),
            cnBaseUrl = etCnBaseUrl.text.toString().ifBlank { "https://api.siliconflow.cn/v1" },
            cnAsrModel = etCnAsrModel.text.toString().ifBlank { "FunAudioLLM/SenseVoiceSmall" },
            cnLlmModel = etCnLlmModel.text.toString().ifBlank { "deepseek-ai/DeepSeek-V3" },
            geminiApiKey = etGeminiKey.text.toString(),
            geminiModel = etGeminiModel.text.toString().ifBlank { "gemini-2.5-flash" },
            whisperApiKey = etWhisperKey.text.toString(),
            whisperBaseUrl = etWhisperUrl.text.toString().ifBlank { "https://api.groq.com/openai/v1" },
            whisperModel = etWhisperModel.text.toString().ifBlank { "whisper-large-v3-turbo" },
            llmApiKey = etLlmKey.text.toString(),
            llmBaseUrl = etLlmUrl.text.toString().ifBlank { "https://api.deepseek.com/v1" },
            llmModel = etLlmModel.text.toString().ifBlank { "deepseek-chat" },
            sourceLang = langCode,
            audioSourceMode = if (spinnerAudioSource.selectedItemPosition == 0) AudioSourceMode.INTERNAL_AUDIO else AudioSourceMode.MICROPHONE,
            fontSizeSp = (14 + seekFontSize.progress).toFloat(),
            bgOpacityPercent = 20 + seekOpacity.progress,
            showOriginalText = cbShowOrig.isChecked,
            customGlossary = etGlossary.text.toString()
        )
        VTransConfig.save(this, settings)
        return settings
    }

    private fun notifyStyleChanged() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingTranslatorService::class.java).apply {
                action = FloatingTranslatorService.ACTION_UPDATE_STYLE
            }
            startService(intent)
        }
    }

    private fun onStartOverlayClicked() {
        val settings = saveUiSettings()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启【悬浮窗权限】", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予【录音权限】", Toast.LENGTH_SHORT).show()
            requestAudioPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        if (settings.audioSourceMode == AudioSourceMode.INTERNAL_AUDIO) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            requestMediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            startTranslatorService(Activity.RESULT_CANCELED, null)
        }
    }

    private fun startTranslatorService(resultCode: Int, data: Intent?) {
        val serviceIntent = Intent(this, FloatingTranslatorService::class.java).apply {
            action = FloatingTranslatorService.ACTION_START
            putExtra(FloatingTranslatorService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) {
                putExtra(FloatingTranslatorService.EXTRA_RESULT_DATA, data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "同传悬浮窗已启动！现在可切去 Bilibili / YouTube 看直播", Toast.LENGTH_LONG).show()
    }
}
