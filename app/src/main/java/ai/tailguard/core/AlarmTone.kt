package ai.tailguard.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 후방 대형차 급접근 경보음 (약 0.9초): 높은음·낮은음을 번갈아 세 번. 짧고 날카로워 음성 안내 전에 바로 주의를 끈다.
 * 순수 Kotlin 이라 JVM 단위 테스트에서 같은 소리를 WAV 로 꺼낼 수 있다 (시연 영상에 앱과 똑같은 소리를 쓰기 위해).
 */
object AlarmTone {
    const val SAMPLE_RATE = 44_100
    private const val HIGH_HZ = 1_250.0
    private const val LOW_HZ = 950.0

    /** (주파수 Hz, 길이 ms) — 0 Hz 는 쉼 */
    private val PATTERN = List(3) { listOf(HIGH_HZ to 110, 0.0 to 25, LOW_HZ to 110, 0.0 to 55) }.flatten()

    /** 최대 음량 = 전체 범위의 80% (배음 합성 후 실제 최댓값으로 맞춘다) */
    private const val PEAK = 0.8

    fun pcm(): ShortArray {
        val wave = ArrayList<Double>()
        for ((hz, ms) in PATTERN) {
            val n = SAMPLE_RATE * ms / 1000
            for (i in 0 until n) {
                if (hz == 0.0) { wave += 0.0; continue }
                val t = i.toDouble() / SAMPLE_RATE
                // 사인 + 3·5배음: 귀에 잘 띄는 거친 음색
                val v = sin(2 * PI * hz * t) + 0.33 * sin(2 * PI * 3 * hz * t) + 0.15 * sin(2 * PI * 5 * hz * t)
                val edge = min(1.0, min(i, n - 1 - i) / (SAMPLE_RATE * 0.004)) // 4ms 페이드로 딸깍 소리 방지
                wave += v * edge
            }
        }
        val max = wave.maxOf { kotlin.math.abs(it) }
        return ShortArray(wave.size) { (wave[it] / max * PEAK * Short.MAX_VALUE).toInt().toShort() }
    }

    /** 16bit mono PCM WAV */
    fun wav(): ByteArray {
        val pcm = pcm()
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(SAMPLE_RATE); b.putInt(SAMPLE_RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}
