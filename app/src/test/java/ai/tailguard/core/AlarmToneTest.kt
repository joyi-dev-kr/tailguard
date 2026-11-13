package ai.tailguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlarmToneTest {

    @Test
    fun `경보음은 1초 안쪽의 짧고 큰 소리`() {
        val pcm = AlarmTone.pcm()
        val seconds = pcm.size.toDouble() / AlarmTone.SAMPLE_RATE
        assertTrue("길이 $seconds", seconds in 0.8..1.0)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("최대 $peak", peak in 20_000..Short.MAX_VALUE.toInt())
    }

    @Test
    fun `WAV 로 꺼내기 - 시연 영상에 같은 소리를 쓰기 위해 build 폴더에 저장`() {
        val wav = AlarmTone.wav()
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + AlarmTone.pcm().size * 2, wav.size)
        File("build").mkdirs()
        File("build/alarm_tone.wav").writeBytes(wav)
    }
}
