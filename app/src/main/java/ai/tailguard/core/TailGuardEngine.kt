package ai.tailguard.core

/**
 * TailGuard 판단 엔진 (안드로이드·차량 SDK 의존 없음 → JVM 단위 테스트 가능).
 *
 * 예고 → 감지 → 경고 → 대비 → 탈출.
 *  - CRUISING        : 평상 주행 (내비 교통정보로 전방 정체가 가까우면 미리 예고: 후방 조명 + 음성)
 *  - TAIL_ENTRY      : 고속 주행 중 급감속 = 정체 꼬리 진입 추정 → 후방 조명 경고 + 음성
 *  - STOPPED_IN_QUEUE: 정체 꼬리에서 정차 → 벨트·자세·탈출공간·조향 방향 안내
 *  - POST_CRASH      : 에어백 전개 → 차량 정지 확인 후 문 잠금 해제·창문 개방·대피 안내
 *
 * 입력은 모두 타임스탬프(ms)를 받는다. 시간 기반 판정만 사용하므로 신호 주기와 무관.
 */
class TailGuardEngine(
    val cfg: TailGuardConfig = TailGuardConfig(),
    private val sink: (Action) -> Unit,
) {
    var phase: Phase = Phase.CRUISING
        private set
    var speedKmh: Float = 0f
        private set
    var steeringDeg: Float = 0f
        private set

    private val speedHistory = ArrayDeque<Pair<Long, Float>>()
    private var stopSinceMs: Long? = null
    private var clearSinceMs: Long? = null
    private var stopBriefed = false
    private var escapeReadyAnnounced = false

    private val occupied = mutableMapOf<Seat, Boolean>().apply { put(Seat.DRIVER, true) }
    private val buckled = mutableMapOf<Seat, Boolean>()

    private var crashed = false
    private var evacuated = false
    private var evacSpoken = 0
    private var lastEvacMs = 0L
    private var stillSinceMs: Long? = null

    /** 전방 정체 예고를 이미 했는지 (같은 정체에 반복 예고 방지) */
    var jamAheadWarned = false
        private set

    /** 마지막으로 받은 전방 정체 거리(m), 정체 없음 = null (화면 표시용) */
    var jamAheadM: Int? = null
        private set

    // ---------- 화면 표시용 읽기 ----------

    fun isOccupied(seat: Seat): Boolean = occupied[seat] == true
    /** true = 착용, false = 미착용, null = 신호 없음 */
    fun beltOf(seat: Seat): Boolean? = buckled[seat]
    val escapeReady: Boolean get() = escapeReadyAnnounced
    /** 충돌 후 문·창문 개방까지 진행했는지 */
    val doorsReleased: Boolean get() = evacuated
    /** 오른쪽 양수 핸들 각도 (부호 관례 보정) */
    val rightPositiveSteeringDeg: Float get() = if (cfg.steeringLeftIsNegative) steeringDeg else -steeringDeg

    // ---------- 입력 ----------

    /**
     * 내비 교통정보: 경로상 가장 가까운 정체(서행·정체) 시작까지 거리(m). 정체가 없으면 null.
     * 고속 순항 중 예고 거리 안에 들어오면 급감속 전에 미리 후방 조명·음성으로 대비시킨다.
     */
    fun onCongestionAhead(aheadM: Int?, nowMs: Long) {
        jamAheadM = aheadM
        if (phase != Phase.CRUISING) return
        if (aheadM == null || aheadM > cfg.jamAheadResetM) {
            if (jamAheadWarned) {
                jamAheadWarned = false
                sink(Action.RearLights(on = false))
                sink(Action.Guidance("정상 주행"))
            }
            return
        }
        if (jamAheadWarned || aheadM > cfg.jamAheadWarnM || speedKmh < cfg.highSpeedKmh) return
        jamAheadWarned = true
        val tenths = (aheadM.coerceAtLeast(100) + 50) / 100 // 0.1 km 단위 반올림 (로케일 무관)
        val km = "${tenths / 10}.${tenths % 10}"
        sink(Action.RearLights(on = true))
        sink(Action.Speak("약 ${km}킬로미터 앞 정체. 미리 속도를 줄이고 비상등을 켜 주세요.", urgent = true))
        sink(Action.Guidance("$km km 앞 정체 — 미리 감속 · 비상등"))
        evaluate(nowMs)
    }

    fun onSpeed(kmh: Float, nowMs: Long) {
        speedKmh = kmh.coerceAtLeast(0f)
        speedHistory.addLast(nowMs to speedKmh)
        while (speedHistory.isNotEmpty() && nowMs - speedHistory.first().first > cfg.tailWindowMs) {
            speedHistory.removeFirst()
        }
        evaluate(nowMs)
    }

    fun onSteeringAngle(deg: Float, nowMs: Long) {
        steeringDeg = deg
        evaluate(nowMs)
    }

    fun onSeatOccupancy(seat: Seat, isOccupied: Boolean) {
        occupied[seat] = isOccupied
    }

    fun onSeatBelt(seat: Seat, isBuckled: Boolean) {
        buckled[seat] = isBuckled
    }

    fun onAirbagDeployed(deployed: Boolean, nowMs: Long) {
        if (!deployed || crashed) return
        crashed = true
        setPhase(Phase.POST_CRASH)
        sink(Action.Speak("충돌이 감지되었습니다. 차량이 멈추면 문을 열어드립니다.", urgent = true))
        evaluate(nowMs)
    }

    /** 신호 변화가 없어도 시간 경과 판정(정차 유지, 대피 안내 반복)을 위해 주기 호출. */
    fun tick(nowMs: Long) = evaluate(nowMs)

    // ---------- 판정 ----------

    private fun evaluate(now: Long) {
        when (phase) {
            Phase.CRUISING -> if (isTailEntry()) enterTail(now)
            Phase.TAIL_ENTRY -> {
                if (checkClear(now)) return
                if (speedKmh <= cfg.stopSpeedKmh) {
                    val since = stopSinceMs ?: now.also { stopSinceMs = it }
                    if (now - since >= cfg.stopHoldMs) enterStopped()
                } else {
                    stopSinceMs = null
                }
            }
            Phase.STOPPED_IN_QUEUE -> {
                if (checkClear(now)) return
                if (speedKmh > cfg.stopSpeedKmh) {
                    // 정체 속 서행 재개: 경고 상태 유지, 다음 정차 시 안내는 생략(중복 방지)
                    stopSinceMs = null
                    setPhase(Phase.TAIL_ENTRY)
                    return
                }
                if (!escapeReadyAnnounced && towardShoulderDeg() >= cfg.escapeReadySteeringDeg) {
                    escapeReadyAnnounced = true
                    sink(Action.Speak("탈출 방향 준비 완료. 브레이크를 밟고 기다리세요."))
                    sink(Action.Guidance("탈출 방향 준비 완료 (${cfg.shoulderSide.label} 갓길)"))
                }
            }
            Phase.POST_CRASH -> handlePostCrash(now)
        }
    }

    private fun isTailEntry(): Boolean {
        if (speedHistory.size < 2) return false
        val peak = speedHistory.maxOf { it.second }
        return peak >= cfg.highSpeedKmh &&
            speedKmh <= cfg.tailSpeedKmh &&
            peak - speedKmh >= cfg.minDropKmh
    }

    private fun enterTail(now: Long) {
        setPhase(Phase.TAIL_ENTRY)
        stopSinceMs = if (speedKmh <= cfg.stopSpeedKmh) now else null
        clearSinceMs = null
        sink(Action.RearLights(on = true))
        sink(Action.Speak("전방 정체, 후방 추돌 주의. 비상등을 켜 주세요.", urgent = true))
        sink(Action.Guidance("정체 꼬리 진입 — 후방 조명 경고 중"))
    }

    private fun enterStopped() {
        setPhase(Phase.STOPPED_IN_QUEUE)
        if (stopBriefed) return
        stopBriefed = true
        escapeReadyAnnounced = false

        val riders = occupied.filterValues { it }.keys
        val unbelted = riders.filter { buckled[it] == false }
        if (unbelted.isNotEmpty()) {
            sink(Action.Speak("${unbelted.joinToString(", ") { it.label }} 좌석 안전벨트를 매 주세요.", urgent = true))
        }
        sink(Action.BackrestUpright(riders.toSet()))
        sink(
            Action.Speak(
                "앞차와 차 한 대 간격을 두세요. 머리를 헤드레스트에 붙이세요."
            )
        )
        sink(Action.Guidance("앞차 간격 확보 · 머리 헤드레스트 · 후방 감시 중"))
    }

    private fun checkClear(now: Long): Boolean {
        if (speedKmh >= cfg.clearSpeedKmh) {
            val since = clearSinceMs ?: now.also { clearSinceMs = it }
            if (now - since >= cfg.clearHoldMs) {
                setPhase(Phase.CRUISING)
                stopSinceMs = null
                clearSinceMs = null
                stopBriefed = false
                escapeReadyAnnounced = false
                jamAheadWarned = false
                speedHistory.clear()
                sink(Action.RearLights(on = false))
                sink(Action.Speak("정체 구간을 벗어났습니다."))
                sink(Action.Guidance("정상 주행"))
                return true
            }
        } else {
            clearSinceMs = null
        }
        return false
    }

    private fun handlePostCrash(now: Long) {
        // 안전: 차량이 움직이는 동안에는 문·창문을 열지 않는다.
        // 추돌 직후 밀려나는 중일 수 있으므로 '정지 상태 지속'까지 확인
        if (!evacuated) {
            if (speedKmh > cfg.safeMoveKmh) {
                stillSinceMs = null
                return
            }
            val since = stillSinceMs ?: now.also { stillSinceMs = it }
            if (now - since < cfg.postCrashStillMs) return
            evacuated = true
            sink(Action.UnlockAllDoors)
            sink(Action.OpenWindows(percent = 100))
            sink(Action.CabinLight(on = true))
            speakEvacuation(now)
            val riders = occupied.filterValues { it }.keys
            sink(
                Action.IncidentReport(
                    occupants = riders.size,
                    unbelted = riders.count { buckled[it] == false },
                    atMs = now,
                )
            )
            return
        }
        if (evacSpoken < cfg.evacRepeatCount && now - lastEvacMs >= cfg.evacRepeatMs) {
            speakEvacuation(now)
        }
    }

    private fun speakEvacuation(now: Long) {
        evacSpoken++
        lastEvacMs = now
        sink(
            Action.Speak(
                "문이 열렸습니다. ${cfg.shoulderSide.label}으로 내려 가드레일 밖으로 대피하세요. " +
                    "차 뒤에 서 있지 마세요.",
                urgent = true,
            )
        )
        sink(Action.Guidance("가드레일 밖으로 대피 · 차 뒤에 서지 마세요"))
    }

    /** 갓길 방향 조향각(양수 = 갓길 쪽). */
    private fun towardShoulderDeg(): Float =
        if (cfg.shoulderSide == Side.RIGHT) rightPositiveSteeringDeg else -rightPositiveSteeringDeg

    private fun setPhase(p: Phase) {
        if (phase == p) return
        phase = p
        sink(Action.PhaseChanged(p))
    }
}

enum class Phase(val label: String) {
    CRUISING("정상 주행"),
    TAIL_ENTRY("정체 꼬리 경고"),
    STOPPED_IN_QUEUE("정차 대비"),
    POST_CRASH("충돌 후 탈출"),
}

enum class Seat(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"),
    REAR_LEFT("뒷좌석 왼쪽"), REAR_CENTER("뒷좌석 가운데"), REAR_RIGHT("뒷좌석 오른쪽"),
}

enum class Side(val label: String) { LEFT("왼쪽"), RIGHT("오른쪽") }

data class TailGuardConfig(
    /** 이 속도 이상 주행 이력이 있어야 '고속 흐름'으로 본다 */
    val highSpeedKmh: Float = 70f,
    /** 이 속도 이하로 떨어지면 정체 꼬리 후보 */
    val tailSpeedKmh: Float = 40f,
    /** 창 안 최고속도 대비 최소 감속 폭 */
    val minDropKmh: Float = 35f,
    /** 급감속 판정 창 */
    val tailWindowMs: Long = 6_000,
    val stopSpeedKmh: Float = 3f,
    val stopHoldMs: Long = 1_000,
    /** 이 속도로 clearHoldMs 이상 유지하면 정체 해소 */
    val clearSpeedKmh: Float = 50f,
    val clearHoldMs: Long = 5_000,
    /** 한국 고속도로 기본: 오른쪽 갓길 */
    val shoulderSide: Side = Side.RIGHT,
    /** 핸들(스티어링 휠) 각도 기준. 바퀴 각도 아님 */
    val escapeReadySteeringDeg: Float = 90f,
    /** AAOS PERF_STEERING_ANGLE 관례: 왼쪽 음수. 실차 확인 필요 */
    val steeringLeftIsNegative: Boolean = true,
    /** 충돌 후 이 속도 이하일 때만 문·창문 개방 */
    val safeMoveKmh: Float = 5f,
    /** 충돌 후 정지 상태가 이 시간 이상 이어져야 개방 */
    val postCrashStillMs: Long = 2_000,
    val evacRepeatMs: Long = 20_000,
    val evacRepeatCount: Int = 3,
    /** 전방 정체까지 이 거리(m) 안이면 예고 (100km/h 기준 약 54초 전) */
    val jamAheadWarnM: Int = 1_500,
    /** 예고 후 정체가 이 거리 밖으로 멀어지거나 사라지면 예고 해제 */
    val jamAheadResetM: Int = 3_000,
)

sealed interface Action {
    data class PhaseChanged(val phase: Phase) : Action
    data class Speak(val text: String, val urgent: Boolean = false) : Action
    data class Guidance(val text: String) : Action
    /** 후미등 + 후방 안개등 */
    data class RearLights(val on: Boolean) : Action
    data class CabinLight(val on: Boolean) : Action
    data class BackrestUpright(val seats: Set<Seat>) : Action
    data object UnlockAllDoors : Action
    data class OpenWindows(val percent: Int) : Action
    data class IncidentReport(val occupants: Int, val unbelted: Int, val atMs: Long) : Action

    // ---------- 후방 접근 감지 ([RearApproachJudge]) ----------
    data class RearApproachWarning(val kind: VehicleKind, val ttcS: Float) : Action
    /** 물리적으로 회피 불가 판정 */
    data class CollisionCertain(val kind: VehicleKind, val ttcS: Float, val requiredDecel: Float) : Action
    /** 바퀴 급속 조향 — 조향 제어는 제조사 영역이라 앱은 화면 시뮬레이션만 한다 */
    data class WheelTurnSim(val deg: Float) : Action
    data object RearApproachCleared : Action
}
