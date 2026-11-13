package ai.tailguard.vehicle

import ai.tailguard.core.AlarmTone
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** [AlarmTone] 경보음을 경보(USAGE_ALARM) 채널로 즉시 재생한다. */
class AlarmPlayer(private val log: (String) -> Unit) {

    private val pcm = AlarmTone.pcm()
    private val track: AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AlarmTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.onFailure { log("경보음 준비 실패: ${it.message}") }.getOrNull()

    fun play() {
        val t = track ?: return
        runCatching {
            t.stop(); t.reloadStaticData(); t.play()
        }.onFailure { log("경보음 재생 실패: ${it.message}") }
    }

    fun release() { track?.release() }
}
