package ai.tailguard

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/**
 * 음성 안내. Pleos 차량은 Pleos TTS(CAAS 온디바이스)를 쓰고,
 * 초기화에 실패하면(일반 안드로이드 기기·에뮬레이터) 안드로이드 TextToSpeech 로 폴백한다.
 */
class Speaker(private val context: Context, private val log: (String) -> Unit) {

    private var pleos: PleosTts? = null
    @Volatile private var pleosReady = false
    private var android: TextToSpeech? = null
    @Volatile private var androidReady = false
    private val pending = mutableListOf<Pair<String, Boolean>>()

    fun start() {
        runCatching {
            PleosTts(context, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { tts.setLocale(Locale.KOREAN) }
                        pleosReady = true
                        log("음성: Pleos TTS 사용")
                        flush()
                    }
                    override fun onFailure(error: ErrorType) {
                        log("Pleos TTS 초기화 실패: $error → 안드로이드 TTS 시도")
                        startAndroid()
                    }
                })
            }
        }.onFailure {
            log("Pleos TTS 사용 불가: ${it.message} → 안드로이드 TTS 시도")
            startAndroid()
        }
    }

    private fun startAndroid() {
        if (android != null) return
        android = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                android?.language = Locale.KOREAN
                androidReady = true
                log("음성: 안드로이드 TTS 사용")
                flush()
            } else {
                log("TTS 초기화 실패 (음성 없이 화면 안내만)")
                pending.clear()
            }
        }
    }

    /** urgent = 진행 중 안내를 끊고 즉시 말함 */
    fun speak(text: String, urgent: Boolean) {
        when {
            pleosReady -> runCatching { pleos?.speak(text, urgent) }.onFailure { log("음성 실패: ${it.message}") }
            androidReady -> android?.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
            else -> { if (urgent) pending.clear(); pending += text to urgent }
        }
    }

    private fun flush() {
        val queued = pending.toList(); pending.clear()
        queued.forEach { (t, u) -> speak(t, u) }
    }

    fun release() {
        runCatching { pleos?.release() }
        android?.shutdown()
    }
}
