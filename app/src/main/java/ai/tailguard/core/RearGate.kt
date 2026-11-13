package ai.tailguard.core

/**
 * 후방 대형차 접근 감지를 지금 켤지 정하는 규칙 (설정 + 지도 위치 + 내비 정체 정보).
 * 안드로이드 의존 없음 → 단위 테스트 가능.
 */
data class RearSettings(
    /** 후방 대형차 접근 감지 전체 켜기/끄기 */
    val enabled: Boolean = true,
    /** 시내 도로에서도 동작 (기본 끔: 시내는 저속·잦은 근접 정차로 경고가 불편할 수 있음) */
    val inCity: Boolean = false,
    /** 내비 정체 구간(전방 정체 1.5km 안 또는 정체 꼬리 경고·정차 대비 단계)에서만 동작 */
    val jamOnly: Boolean = false,
)

enum class RoadType(val label: String) {
    HIGHWAY("고속도로·자동차전용도로"), CITY("시내 도로"), UNKNOWN("도로 정보 없음"),
}

/** 지도 위치(내비) 정보로 도로 구분: 주소에 고속도로 명칭, 또는 현재 도로 제한속도 */
object RoadClassifier {
    private val highwayWords = listOf("고속도로", "고속국도", "도시고속", "자동차전용")
    /** 이 제한속도 이상이면 고속도로·자동차전용도로로 본다 (국내 시내 일반도로는 대부분 60km/h 이하) */
    const val HIGHWAY_LIMIT_KMH = 80

    fun classify(speedLimitKmh: Int?, address: String?): RoadType = when {
        address != null && highwayWords.any { it in address } -> RoadType.HIGHWAY
        speedLimitKmh != null && speedLimitKmh >= HIGHWAY_LIMIT_KMH -> RoadType.HIGHWAY
        speedLimitKmh != null && speedLimitKmh > 0 -> RoadType.CITY
        else -> RoadType.UNKNOWN
    }
}

object RearGate {
    data class Gate(val active: Boolean, val reason: String)

    /**
     * @param inJam 내비 기준 정체 구간 여부 (전방 정체 1.5km 안, 또는 엔진이 정체 꼬리 경고·정차 대비 단계)
     * 도로 정보가 없으면(UNKNOWN) 막지 않는다 — 고속도로에서 정보가 끊겨도 보호가 꺼지지 않도록.
     */
    fun evaluate(s: RearSettings, road: RoadType, inJam: Boolean): Gate = when {
        !s.enabled -> Gate(false, "후방 감지 꺼짐 (설정)")
        road == RoadType.CITY && !s.inCity -> Gate(false, "시내 도로 — 시내 감지 꺼짐 (설정)")
        s.jamOnly && !inJam -> Gate(false, "정체 구간 아님 — 정체 구간에서만 동작 (설정)")
        else -> Gate(true, "후방 감시 중")
    }

    fun inJam(engine: TailGuardEngine): Boolean {
        val jam = engine.jamAheadM
        return engine.phase == Phase.TAIL_ENTRY || engine.phase == Phase.STOPPED_IN_QUEUE ||
            (jam != null && jam <= engine.cfg.jamAheadWarnM)
    }
}
