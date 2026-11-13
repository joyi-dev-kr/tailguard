package ai.tailguard.core

/**
 * 시연용 시나리오: 봉평터널 사고 상황 재현 (고속 주행 → 터널 입구 정체 급감속 → 정차 → 후방 추돌).
 * 실차·에뮬레이터 신호 없이도 엔진 전체 흐름을 보여주기 위한 입력 열.
 */
object DemoScenario {

    /** 추돌(에어백) 시각. 시연 앱은 이 앞까지 재생 → 후방 영상 인식 → 이후를 이어서 재생한다 */
    const val CRASH_AT_MS = 22_000L

    sealed interface Event {
        val atMs: Long
        data class Speed(override val atMs: Long, val kmh: Float) : Event
        data class Steering(override val atMs: Long, val deg: Float) : Event
        data class Belt(override val atMs: Long, val seat: Seat, val buckled: Boolean) : Event
        data class Occupancy(override val atMs: Long, val seat: Seat, val occupied: Boolean) : Event
        data class Airbag(override val atMs: Long) : Event
        /** 내비 교통정보: 전방 정체까지 거리(m), null = 정체 없음 */
        data class JamAhead(override val atMs: Long, val aheadM: Int?) : Event
        data class Tick(override val atMs: Long) : Event
    }

    /** 4명 탑승, 뒷좌석 오른쪽 벨트 미착용 → 경고 후 착용, 정차 중 후방 대형차 접근 → 추돌. */
    fun bongpyeong(): List<Event> = buildList {
        val riders = listOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
        riders.forEach { add(Event.Occupancy(0, it, true)) }
        riders.forEach { add(Event.Belt(0, it, it != Seat.REAR_RIGHT)) }
        // 0~6초: 100km/h 순항. 1초: 내비가 1.2km 앞 터널 입구 정체를 알림 → 예고, 이후 거리 갱신
        for (t in 0L..6_000L step 500) add(Event.Speed(t, 100f))
        add(Event.JamAhead(1_000, 1_200))
        add(Event.JamAhead(3_000, 800))
        add(Event.JamAhead(5_000, 400))
        add(Event.JamAhead(6_000, 200))
        // 6.5~9.5초: 정체 꼬리 발견, 100 → 0 급감속
        val decel = listOf(85f, 65f, 45f, 30f, 15f, 5f, 0f)
        decel.forEachIndexed { i, v -> add(Event.Speed(6_500L + i * 500, v)) }
        // 정차 유지 (1초 유지 후 정차 대비 단계)
        for (t in 10_000L..21_500L step 500) add(Event.Speed(t, 0f))
        // 벨트 경고를 듣고 뒷좌석 오른쪽 착용
        add(Event.Belt(14_500, Seat.REAR_RIGHT, true))
        // 17.5~21.5초: 후방 카메라에 관광버스가 다가옴 (MainActivity 가 후방 시연 영상을 같은 시각에 재생)
        // 22초: 후방 추돌 → 에어백 전개, 차량이 밀리며 잠시 움직임
        add(Event.Speed(22_000, 0f))
        add(Event.Airbag(22_000))
        add(Event.Speed(22_200, 18f))
        add(Event.Speed(23_000, 6f))
        add(Event.Speed(23_500, 0f))
        // 정지 확인 → 문 개방, 대피 안내 반복 확인
        for (t in 24_000L..30_000L step 500) add(Event.Tick(t))
        for (t in 31_000L..70_000L step 1_000) add(Event.Tick(t))
    }.sortedBy { it.atMs } // 시각순 (같은 시각은 추가 순서 유지)

    fun apply(engine: TailGuardEngine, e: Event) = when (e) {
        is Event.Speed -> engine.onSpeed(e.kmh, e.atMs)
        is Event.Steering -> engine.onSteeringAngle(e.deg, e.atMs)
        is Event.Belt -> engine.onSeatBelt(e.seat, e.buckled)
        is Event.Occupancy -> engine.onSeatOccupancy(e.seat, e.occupied)
        is Event.Airbag -> engine.onAirbagDeployed(true, e.atMs)
        is Event.JamAhead -> engine.onCongestionAhead(e.aheadM, e.atMs)
        is Event.Tick -> engine.tick(e.atMs)
    }
}
