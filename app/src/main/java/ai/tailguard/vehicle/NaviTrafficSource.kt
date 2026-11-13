package ai.tailguard.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pleos NaviHelper 교통정보 → 전방 정체 거리.
 * 경로 안내 중 RouteStateInfo.trafficStatus 의 정체 구간(SLOW·HEAVY) 중 가장 가까운 시작점까지 거리를 전달한다.
 * Pleos ADAS SDK(후방 물체 정보)는 아직 공개 전이라, 정체 꼬리를 '미리' 아는 공개 경로는 내비 교통정보뿐.
 *
 * offset 을 '현재 위치에서 정체 시작까지 거리(m)'로 해석 (SDK 문서에 단위 설명 없음 → 실차·에뮬레이터 로그로 확인).
 *
 * 지도 위치(도로 구분)도 함께 받는다: 현재 위치 주소(getCurrentLocationInfo)와 현재 도로 제한속도(onCurrentRoadSpeedLimit).
 * → [ai.tailguard.core.RoadClassifier] 가 고속도로/시내를 가른다 (후방 감지 설정의 '시내에서 동작' 판단용).
 */
class NaviTrafficSource(
    context: Context,
    private val onJamAhead: (aheadM: Int?, nowMs: Long) -> Unit,
    private val log: (String) -> Unit,
    private val onRoad: (speedLimitKmh: Int?, address: String?) -> Unit = { _, _ -> },
) {
    private var speedLimit: Int? = null
    private var address: String? = null
    private val navi = NaviHelper(context)
    private val main = Handler(Looper.getMainLooper())
    private var lastRaw: String? = null
    private var running = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(info: RouteStateInfo) = handle(info)
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            address = info.address
            main.post { onRoad(speedLimit, address) }
        }
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            speedLimit = limit.takeIf { it > 0 }
            main.post { onRoad(speedLimit, address) }
        }
        override fun onError(errorCode: NaviErrorCode) = log("내비 교통정보 실패: $errorCode")
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { navi.getRouteStateInfo() }.onFailure { log("내비 경로상태 요청 실패: ${it.message}") }
            runCatching { navi.getCurrentLocationInfo() }.onFailure { log("내비 현재 위치 요청 실패: ${it.message}") }
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        running = true
        navi.initialize({
            navi.addListener(listener)
            log("내비 교통정보 연결")
            main.post(poll)
        }, { log("내비 연결 실패: ${it.message}") })
    }

    fun stop() {
        running = false
        main.removeCallbacks(poll)
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    private fun handle(info: RouteStateInfo) {
        val jams = info.trafficStatus?.congestionItemList.orEmpty()
            .filter { it.trafficCongestionType == TrafficCongestionType.SLOW || it.trafficCongestionType == TrafficCongestionType.HEAVY }
        val nearest = jams.minByOrNull { it.offset }
        // 원시값은 바뀔 때만 기록 (offset 단위·기준 확인용)
        val raw = nearest?.let { "offset=${it.offset} 길이=${it.distance} 평균=${it.avrSpeed} ${it.trafficCongestionType} ${it.roadName}" } ?: "정체 없음"
        if (raw != lastRaw) { lastRaw = raw; log("내비 정체: $raw (전체 ${jams.size}구간)") }
        val ahead = nearest?.offset?.takeIf { it >= 0 }?.toInt()
        main.post { onJamAhead(ahead, System.currentTimeMillis()) }
    }

    private companion object { const val POLL_MS = 10_000L }
}
