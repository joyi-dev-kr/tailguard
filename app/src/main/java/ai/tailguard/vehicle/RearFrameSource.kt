package ai.tailguard.vehicle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 후방 카메라 프레임 공급원.
 * 실차: Pleos 카메라 영상 API(PleosCameraManager.startVideoStream) 연결 예정 — 일반 앱 사용 가능 여부 확인 전이라 아직 없음.
 * 시연: 앱에 넣은 후방 시연 영상 프레임(assets/rear_demo/NNN.jpg, 10fps)을 같은 인식·판정 경로로 흘려보낸다.
 * 검증: 앱 전용 폴더(files/rear_test/NNN.jpg, 10fps)에 프레임이 있으면 그것을 쓴다.
 *       실제 사고 블랙박스 영상처럼 앱에 넣어 배포할 수 없는 영상으로 시험할 때 사용. hfov.txt 에 카메라 수평 화각(도)을 적을 수 있다.
 */
class DemoClipFrames(private val context: Context, private val log: (String) -> Unit) {

    @Volatile var frames: List<Bitmap> = emptyList()
        private set
    /** 검증 영상의 카메라 수평 화각 (없으면 판정기 기본값) */
    @Volatile var hfovDeg: Double? = null
        private set
    @Volatile var sourceLabel: String = "시연: 재연 영상"
        private set

    /** 백그라운드 스레드에서 호출 */
    fun load() {
        if (frames.isNotEmpty()) return
        runCatching {
            // 앱 전용 폴더(내부 files/rear_test → 외부 files/rear_test 순)에서 검증 프레임을 찾는다
            val ext = listOfNotNull(File(context.filesDir, TEST_DIR), context.getExternalFilesDir(TEST_DIR))
                .firstOrNull { d -> d.listFiles { f -> f.name.endsWith(".jpg") }?.isNotEmpty() == true }
            val extFiles = ext?.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
            if (extFiles.isNotEmpty()) {
                frames = extFiles.mapNotNull { BitmapFactory.decodeFile(it.path) }
                hfovDeg = File(ext, "hfov.txt").takeIf { it.exists() }?.readText()?.trim()?.toDoubleOrNull()
                sourceLabel = "검증: 실제 영상"
                log("후방 검증 영상 ${frames.size}프레임 준비 (화각 ${hfovDeg ?: "기본"}°)")
                return
            }
            val names = context.assets.list(DIR).orEmpty().filter { it.endsWith(".jpg") }.sorted()
            frames = names.mapNotNull { n -> context.assets.open("$DIR/$n").use { BitmapFactory.decodeStream(it) } }
            log("후방 시연 영상 ${frames.size}프레임 준비")
        }.onFailure { log("후방 시연 영상 준비 실패: ${it.message}") }
    }

    companion object {
        const val DIR = "rear_demo"
        const val TEST_DIR = "rear_test"
        const val FPS = 10
    }
}
