package ai.tailguard.core

import kotlin.math.max
import kotlin.math.tan

/**
 * 후방 대형차 급속 접근 판정 (안드로이드·카메라 의존 없음 → JVM 단위 테스트 가능).
 *
 * 입력: 후방 카메라 한 프레임에서 찾은 가장 가까운 뒤차 ([RearDetection], 없으면 null).
 * 거리 센서 없이 영상만으로 판단한다.
 *  - 충돌까지 남은 시간 τ = 박스 폭 ÷ 박스 폭 증가 속도 (단안 카메라 표준 기법, 실제 거리·속도 불필요)
 *  - 거리 d ≈ 차폭 × 초점거리 ÷ 박스 폭 (차종별 대표 차폭, 카메라 화각으로 초점거리 환산)
 *  - 접근 속도 v = d ÷ τ, 뒤차가 지금 멈추려면 필요한 감속도 a = v² ÷ 2d
 *
 * 단계:
 *  - [RearLevel.WARN]    : 대형차가 τ 안에 닿을 만큼 빠르게 접근 → 후방 조명 점멸·"충격 대비" 음성
 *  - [RearLevel.CERTAIN] : 필요 감속도가 대형차가 낼 수 있는 최대 감속도를 넘음 = 물리적으로 회피 불가
 *                          → 생존 자세 (휠 급속 조향은 제조사 영역이라 앱에서는 시뮬레이션)
 * 같은 조건이 연속 [RearJudgeConfig.confirmFrames] 프레임 이어져야 단계가 오른다 (한 프레임 오검출 무시).
 */
class RearApproachJudge(
    val cfg: RearJudgeConfig = RearJudgeConfig(),
    private val sink: (Action) -> Unit,
) {
    var level: RearLevel = RearLevel.NONE
        private set
    /** 화면 표시용 마지막 추정값 (추정 불가 = null) */
    var last: RearEstimate? = null
        private set

    private val samples = ArrayDeque<Pair<Long, Float>>() // (시각 ms, 박스 폭 비율)
    /** 같은 차의 최근 차종 판정 (인식기는 같은 트럭을 프레임마다 버스·트럭·승용차로 바꿔 부르기도 한다) */
    private val kinds = ArrayDeque<VehicleKind>()
    private var lastSeenMs = 0L
    private var warnStreak = 0
    private var certainStreak = 0
    private var certainAtMs: Long? = null

    /** 내 차 속도. 정체 꼬리(저속·정지)에서만 판정한다. */
    var egoKmh: Float = 0f

    fun onFrame(det: RearDetection?, nowMs: Long) {
        if (det == null) {
            if (nowMs - lastSeenMs > cfg.lostTrackMs) resetTrack(nowMs)
            return
        }
        // 박스가 갑자기 작아지면 다른 차로 보고 추적을 새로 시작 (차종 이름이 바뀌는 것만으로는 새로 시작하지 않음)
        val prev = samples.lastOrNull()
        if (prev != null && det.widthFrac < prev.second * 0.6f) { samples.clear(); kinds.clear() }
        lastSeenMs = nowMs
        samples.addLast(nowMs to det.widthFrac)
        kinds.addLast(det.kind)
        while (samples.size > 2 && nowMs - samples.first().first > cfg.windowMs) { samples.removeFirst(); kinds.removeFirst() }
        evaluate(trackKind(), nowMs)
    }

    /** 최근 판정의 1/3 이상이 버스·트럭이면 대형차로 본다 (버스·트럭은 한 부류). 대형차 중 많이 나온 쪽 이름을 쓴다. */
    private fun trackKind(): VehicleKind {
        val large = kinds.filter { it.large }
        if (large.isEmpty() || large.size * 3 < kinds.size) return VehicleKind.CAR
        return large.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    private fun evaluate(kind: VehicleKind, now: Long) {
        val det = RearDetection(kind, samples.last().second, 1f)
        val est = estimate(det) ?: run { last = null; warnStreak = 0; certainStreak = 0; return }
        last = est
        val gated = egoKmh <= cfg.maxEgoKmh && kind.large && est.closingMps >= cfg.minClosingMps
        val warn = gated && est.ttcS <= cfg.warnTtcS
        val certain = gated && (est.requiredDecel >= cfg.maxHeavyDecel || est.ttcS <= cfg.certainTtcS)
        warnStreak = if (warn) warnStreak + 1 else 0
        certainStreak = if (certain) certainStreak + 1 else 0

        if (level == RearLevel.NONE && warnStreak >= cfg.confirmFrames) {
            level = RearLevel.WARN
            sink(Action.RearApproachWarning(det.kind, est.ttcS))
            sink(Action.RearLights(on = true))
            sink(Action.Speak("후방 ${det.kind.label} 급접근. 충격에 대비하세요.", urgent = true))
        }
        if (level != RearLevel.CERTAIN && certainStreak >= cfg.confirmFrames) {
            level = RearLevel.CERTAIN
            certainAtMs = now
            sink(Action.CollisionCertain(det.kind, est.ttcS, est.requiredDecel))
            sink(Action.WheelTurnSim(cfg.wheelSimDeg))
        }
    }

    /**
     * 박스 폭의 역수 1/w 는 거리에 비례하므로, 일정 속도로 다가오면 시간에 대해 직선이 된다.
     * 그 직선의 기울기를 두 점씩 짝지은 기울기의 중앙값으로 구한다 (Theil–Sen: 튀는 프레임 한두 장에 흔들리지 않음).
     * 현재값 y = 1/w 를 직선에서 읽어 τ = y ÷ (−기울기). 표본이 모자라거나 가까워지지 않으면 null.
     */
    private fun estimate(det: RearDetection): RearEstimate? {
        if (samples.size < cfg.minSamples) return null
        val span = samples.last().first - samples.first().first
        if (span < cfg.minSpanMs) return null
        val pts = samples.filter { it.second > 1e-4f }.map { it.first to 1.0 / it.second }
        if (pts.size < cfg.minSamples) return null
        val slopes = ArrayList<Double>(pts.size * pts.size / 2)
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val dt = (pts[j].first - pts[i].first) / 1000.0
            if (dt > 0) slopes += (pts[j].second - pts[i].second) / dt
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        val slope = slopes[slopes.size / 2] // (1/박스 폭) / 초, 다가오면 음수
        if (slope >= -1e-6) return null
        val tEnd = pts.last().first
        val y = pts.map { it.second - slope * (it.first - tEnd) / 1000.0 }.sorted()[pts.size / 2]
        if (y <= 1e-6) return null
        val ttc = (y / -slope).toFloat()
        val focal = 0.5 / tan(Math.toRadians(cfg.cameraHfovDeg / 2.0))
        val dist = (det.kind.widthM * focal * y).toFloat()
        val v = dist / max(ttc, 0.05f)
        val need = v * v / (2f * max(dist, 0.5f))
        return RearEstimate(det.kind, ttc, dist, v, need)
    }

    private fun resetTrack(now: Long) {
        samples.clear(); kinds.clear(); last = null; warnStreak = 0; certainStreak = 0
        val holdOver = certainAtMs?.let { now - it > cfg.certainHoldMs } ?: true
        if (level != RearLevel.NONE && holdOver) {
            level = RearLevel.NONE
            certainAtMs = null
            sink(Action.RearApproachCleared)
        }
    }

    fun reset() {
        samples.clear(); kinds.clear(); last = null; warnStreak = 0; certainStreak = 0
        level = RearLevel.NONE; certainAtMs = null
    }
}

enum class VehicleKind(val label: String, val widthM: Float, val large: Boolean) {
    CAR("승용차", 1.85f, false), BUS("버스", 2.5f, true), TRUCK("대형 트럭", 2.5f, true),
}

/** 한 프레임의 뒤차 검출. widthFrac = 박스 폭 ÷ 프레임 폭 (0~1) */
data class RearDetection(val kind: VehicleKind, val widthFrac: Float, val score: Float)

data class RearEstimate(
    val kind: VehicleKind,
    /** 충돌까지 남은 시간 (초) */
    val ttcS: Float,
    /** 추정 거리 (m) */
    val distM: Float,
    /** 추정 접근 속도 (m/s) */
    val closingMps: Float,
    /** 뒤차가 지금 멈추려면 필요한 감속도 (m/s²) */
    val requiredDecel: Float,
)

enum class RearLevel(val label: String) { NONE("후방 감시 중"), WARN("후방 대형차 급접근"), CERTAIN("충돌 확실") }

data class RearJudgeConfig(
    /** 후방 카메라 수평 화각 (광각 후방 카메라 가정, 실차 값으로 교체) */
    val cameraHfovDeg: Double = 100.0,
    /** 위험 접근 경고: 충돌까지 남은 시간 (초) */
    val warnTtcS: Float = 4.0f,
    /** 남은 시간이 이보다 짧으면 감속도와 무관하게 충돌 확실 */
    val certainTtcS: Float = 0.8f,
    /** 대형차가 낼 수 있는 최대 감속도 (m/s², 설계값 — 실측 자료로 조정) */
    val maxHeavyDecel: Float = 6.5f,
    /**
     * 이보다 느리게 다가오면 반응하지 않는다 (m/s ≈ 30 km/h).
     * 저속 추돌·차간거리를 좁혀 붙는 차까지 경고하면 불편만 커지므로 '급속 접근'만 본다.
     */
    val minClosingMps: Float = 30f / 3.6f,
    /** 내 차가 이보다 빠르면 판정하지 않음 (정체 꼬리 상황 한정) */
    val maxEgoKmh: Float = 30f,
    val windowMs: Long = 1_200,
    val minSamples: Int = 4,
    val minSpanMs: Long = 300,
    val confirmFrames: Int = 2,
    val lostTrackMs: Long = 800,
    /** 충돌 확실 판정 후 이 시간 동안은 추적을 잃어도 상태 유지 (충돌 순간 화면이 가려짐) */
    val certainHoldMs: Long = 5_000,
    /** 충돌 확실 시 바퀴 조향 시뮬레이션 각도 */
    val wheelSimDeg: Float = 45f,
)
