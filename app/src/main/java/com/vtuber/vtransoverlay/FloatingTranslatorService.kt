package com.vtuber.vtransoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatingTranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.vtuber.vtransoverlay.ACTION_START"
        const val ACTION_UPDATE_STYLE = "com.vtuber.vtransoverlay.ACTION_UPDATE_STYLE"
        const val ACTION_STOP = "com.vtuber.vtransoverlay.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "vtrans_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayRoot: LinearLayout? = null
    private var unlockPillView: TextView? = null
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var pillParams: WindowManager.LayoutParams

    private lateinit var statusDot: TextView
    private lateinit var langBadge: TextView
    private lateinit var btnPause: TextView
    private lateinit var btnLock: TextView
    private lateinit var prevSubtitleView: TextView
    private lateinit var origSubtitleView: TextView
    private lateinit var zhSubtitleView: TextView

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val translationEngine = TranslationEngine()

    @Volatile
    private var isPaused = false
    private var isTouchLocked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        setupFloatingWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STYLE -> {
                applyOverlayStyle(VTransConfig.load(this))
                return START_STICKY
            }
            ACTION_START -> {
                val settings = VTransConfig.load(this)
                applyOverlayStyle(settings)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                startForegroundWithType(settings.audioSourceMode == AudioSourceMode.INTERNAL_AUDIO && resultData != null)
                startAudioCaptureLoop(settings, resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithType(useMediaProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (useMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0
            }
            startForeground(NOTIFICATION_ID, notification, fgType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingWindows() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // 1. 主同传字幕悬浮窗
        overlayParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        // 顶部控制栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        statusDot = TextView(this).apply {
            text = "● "
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        langBadge = TextView(this).apply {
            text = "VTuber 同传运行中"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makeActionBtn(label: String): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
        }

        btnPause = makeActionBtn("⏸ 暂停").apply {
            setOnClickListener {
                isPaused = !isPaused
                text = if (isPaused) "▶ 继续" else "⏸ 暂停"
                statusDot.setTextColor(if (isPaused) Color.parseColor("#94A3B8") else Color.parseColor("#4ADE80"))
                langBadge.text = if (isPaused) "已暂停同传" else "正在聆听直播..."
            }
        }

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }

        btnLock = makeActionBtn("🔒 穿透").apply {
            setOnClickListener {
                setTouchLockMode(true)
            }
        }

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }

        val btnClose = makeActionBtn("✕").apply {
            setOnClickListener { stopSelf() }
        }

        header.addView(statusDot)
        header.addView(langBadge)
        header.addView(btnPause)
        header.addView(spacer1)
        header.addView(btnLock)
        header.addView(spacer2)
        header.addView(btnClose)

        // 上一句历史字幕（半透明弱化显示，方便连贯阅读）
        prevSubtitleView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }

        // 当前识别出的日/英原文字幕
        origSubtitleView = TextView(this).apply {
            text = "等待 VTuber 开口说话..."
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(2))
        }

        // 当前中文同传译文字幕（高对比度醒目显示）
        zhSubtitleView = TextView(this).apply {
            text = "悬浮窗已就绪，现在可以切换到 YouTube / Twitch 观看直播"
            setTextColor(Color.parseColor("#FEF08A"))
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }

        root.addView(header)
        root.addView(prevSubtitleView)
        root.addView(origSubtitleView)
        root.addView(zhSubtitleView)

        // 支持按住悬浮窗任意位置自由拖拽移动
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        root.setOnTouchListener { _, event ->
            if (isTouchLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams.x
                    initialY = overlayParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    overlayParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(root, overlayParams)
                    true
                }
                else -> false
            }
        }

        overlayRoot = root
        windowManager.addView(root, overlayParams)

        // 2. 点击穿透模式下的迷你解锁胶囊按钮（停靠在屏幕右侧边缘）
        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(120)
        }

        val pill = TextView(this).apply {
            text = "🔓 解锁字幕窗"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC2563EB"))
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
            setOnClickListener {
                setTouchLockMode(false)
            }
        }

        unlockPillView = pill
        windowManager.addView(pill, pillParams)
        applyOverlayStyle(VTransConfig.load(this))
    }

    /**
     * 切换“点击穿透锁定模式”：
     * 开启后，主字幕悬浮窗完全不拦截任何手指点击（FLAG_NOT_TOUCHABLE），
     * 用户可以直接透过字幕点击下方的 YouTube/Twitch 播放器、发弹幕或切全屏。
     */
    private fun setTouchLockMode(locked: Boolean) {
        isTouchLocked = locked
        val root = overlayRoot ?: return
        if (locked) {
            overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            windowManager.updateViewLayout(root, overlayParams)
            unlockPillView?.visibility = View.VISIBLE
            langBadge.text = "🔒 点击穿透已开启（不挡屏幕操作）"
        } else {
            overlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            windowManager.updateViewLayout(root, overlayParams)
            unlockPillView?.visibility = View.GONE
            langBadge.text = "VTuber 同传运行中"
        }
    }

    private fun applyOverlayStyle(settings: AppSettings) {
        val root = overlayRoot ?: return
        val density = resources.displayMetrics.density
        val alpha = (settings.bgOpacityPercent.coerceIn(20, 95) * 255) / 100
        root.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, 15, 23, 42))
            cornerRadius = 14f * density
            setStroke((1 * density).toInt(), Color.argb(90, 148, 163, 184))
        }
        zhSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
        origSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSizeSp - 4f).coerceAtLeast(12f))
        prevSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (settings.fontSizeSp - 5f).coerceAtLeast(11f))
        origSubtitleView.visibility = if (settings.showOriginalText) View.VISIBLE else View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop(settings: AppSettings, resultCode: Int, resultData: Intent?) {
        captureJob?.cancel()
        stopAudioResources()

        captureJob = serviceScope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            val record: AudioRecord? = try {
                if (settings.audioSourceMode == AudioSourceMode.INTERNAL_AUDIO && resultData != null && resultCode == Activity.RESULT_OK) {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mp = mpManager.getMediaProjection(resultCode, resultData)
                    mediaProjection = mp
                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBufferSize * 2)
                        .build()
                } else {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize * 2
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    zhSubtitleView.text = "音频初始化失败: ${e.localizedMessage}"
                }
                null
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) {
                    zhSubtitleView.text = "无法启动录音器，请确认已授予录音与屏幕内录权限"
                }
                return@launch
            }

            audioRecord = record
            record.startRecording()

            withContext(Dispatchers.Main) {
                val modeName = if (mediaProjection != null) "系统内录" else "麦克风"
                langBadge.text = "正在监听 ($modeName | ${settings.sourceLang.uppercase()})"
            }

            // 每次读取 100ms (1600 个采样点) 进行动态静音断句 (VAD)
            val frameSamples = SAMPLE_RATE / 10
            val readBuffer = ShortArray(frameSamples)
            val speechAccumulator = ArrayList<Short>(SAMPLE_RATE * 6)

            var speakingFrames = 0
            var silenceFrames = 0
            val minSpeechFrames = 16 // 至少说话 1.6 秒才允许遇到停顿断句
            val pauseSilenceFrames = 4 // 连续 400ms 静音视为自然句尾停顿
            val maxSpeechFrames = (settings.chunkSeconds * 10).toInt().coerceIn(25, 60)

            while (isActive) {
                if (isPaused) {
                    kotlinx.coroutines.delay(200)
                    continue
                }

                val readCount = record.read(readBuffer, 0, frameSamples)
                if (readCount <= 0) continue

                val currentSettings = VTransConfig.load(this@FloatingTranslatorService)
                val rms = WavUtil.calculateRms(readBuffer, readCount)
                val isVoice = rms >= currentSettings.vadSilenceThreshold

                if (isVoice) {
                    speakingFrames++
                    silenceFrames = 0
                    for (i in 0 until readCount) {
                        speechAccumulator.add(readBuffer[i])
                    }
                } else if (speakingFrames > 0) {
                    silenceFrames++
                    for (i in 0 until readCount) {
                        speechAccumulator.add(readBuffer[i])
                    }
                }

                val hitNaturalPause = speakingFrames >= minSpeechFrames && silenceFrames >= pauseSilenceFrames
                val hitMaxLength = speakingFrames >= maxSpeechFrames

                if (hitNaturalPause || hitMaxLength) {
                    val pcmSlice = ShortArray(speechAccumulator.size)
                    for (i in speechAccumulator.indices) {
                        pcmSlice[i] = speechAccumulator[i]
                    }
                    speechAccumulator.clear()
                    speakingFrames = 0
                    silenceFrames = 0

                    val wavBytes = WavUtil.pcmToWavBytes(pcmSlice, SAMPLE_RATE)

                    // 异步并发发送给同传引擎，不阻塞下一句直播录音
                    serviceScope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            statusDot.setTextColor(Color.parseColor("#FBBF24")) // 黄色代表翻译请求中
                        }
                        val sub = translationEngine.processAudioChunk(wavBytes, currentSettings)
                        withContext(Dispatchers.Main) {
                            statusDot.setTextColor(Color.parseColor("#4ADE80"))
                            if (sub != null && sub.translatedText.isNotBlank()) {
                                val oldZh = zhSubtitleView.text.toString()
                                if (oldZh.isNotBlank() && !oldZh.startsWith("悬浮窗已就绪")) {
                                    prevSubtitleView.text = oldZh
                                    prevSubtitleView.visibility = View.VISIBLE
                                }
                                origSubtitleView.text = sub.originalText
                                zhSubtitleView.text = sub.translatedText
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopAudioResources() {
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        runCatching {
            mediaProjection?.stop()
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        stopAudioResources()
        runCatching { overlayRoot?.let { windowManager.removeView(it) } }
        runCatching { unlockPillView?.let { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VTuber 同传悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持直播同声传译与悬浮字幕在后台持续运行"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingTranslatorService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VTrans 同传悬浮窗运行中")
            .setContentText("正在实时监听并翻译 VTuber 直播语音")
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭同传", pendingStop)
            .setOngoing(true)
            .build()
    }
}
