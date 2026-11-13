package ai.tailguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailGuardEngineTest {

    private val actions = mutableListOf<Action>()
    private val engine = TailGuardEngine(sink = { actions += it })

    private fun drive(vararg points: Pair<Long, Float>) = points.forEach { (t, v) -> engine.onSpeed(v, t) }
    private inline fun <reified T : Action> taken() = actions.filterIsInstance<T>()

    @Test
    fun `고속에서 급감속하면 정체 꼬리 경고`() {
        drive(0L to 100f, 1000L to 100f, 2000L to 80f, 3000L to 50f, 4000L to 35f)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        assertTrue(Action.RearLights(true) in actions)
        assertTrue(taken<Action.Speak>().any { it.urgent && "후방 추돌" in it.text })
    }

    @Test
    fun `저속 시내 정지는 경고하지 않음`() {
        drive(0L to 50f, 1000L to 40f, 2000L to 20f, 3000L to 0f, 5000L to 0f)
        assertEquals(Phase.CRUISING, engine.phase)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `완만한 감속은 창 밖이면 경고하지 않음`() {
        // 100 → 35 를 20초에 걸쳐 감속 (6초 창 안 감속폭 < 35)
        var t = 0L
        var v = 100f
        while (v > 35f) { engine.onSpeed(v, t); t += 1000; v -= 3.25f }
        engine.onSpeed(35f, t)
        assertEquals(Phase.CRUISING, engine.phase)
    }

    @Test
    fun `정차 1초 유지 시 대비 안내와 미착용 벨트 경고`() {
        engine.onSeatOccupancy(Seat.REAR_LEFT, true)
        engine.onSeatBelt(Seat.DRIVER, true)
        engine.onSeatBelt(Seat.REAR_LEFT, false)
        drive(0L to 100f, 1000L to 70f, 2000L to 30f, 3000L to 0f)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        engine.tick(3500)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        engine.tick(4000)
        assertEquals(Phase.STOPPED_IN_QUEUE, engine.phase)
        assertTrue(taken<Action.Speak>().any { "뒷좌석 왼쪽" in it.text && "안전벨트" in it.text })
        assertTrue(taken<Action.Speak>().none { "운전석" in it.text && "안전벨트" in it.text })
        assertEquals(setOf(Seat.DRIVER, Seat.REAR_LEFT), taken<Action.BackrestUpright>().single().seats)
    }

    @Test
    fun `갓길 방향으로 핸들을 돌리면 준비 완료 1회`() {
        drive(0L to 100f, 1000L to 60f, 2000L to 20f, 3000L to 0f)
        engine.tick(4100)
        engine.onSteeringAngle(-120f, 4200) // 왼쪽 = 반대 방향
        assertTrue(taken<Action.Speak>().none { "준비 완료" in it.text })
        engine.onSteeringAngle(100f, 4300)
        engine.onSteeringAngle(130f, 4400)
        assertEquals(1, taken<Action.Speak>().count { "준비 완료" in it.text })
    }

    @Test
    fun `정체 해소 5초 유지 시 조명 원복`() {
        drive(0L to 100f, 1000L to 60f, 2000L to 20f)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        drive(3000L to 60f, 5000L to 70f, 7000L to 80f)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        drive(8000L to 80f)
        assertEquals(Phase.CRUISING, engine.phase)
        assertTrue(Action.RearLights(false) in actions)
    }

    @Test
    fun `서행 반복 시 정차 안내는 한 번만`() {
        drive(0L to 100f, 1000L to 60f, 2000L to 0f)
        engine.tick(3100)
        drive(4000L to 10f, 5000L to 0f)
        engine.tick(6100)
        assertEquals(Phase.STOPPED_IN_QUEUE, engine.phase)
        assertEquals(1, taken<Action.BackrestUpright>().size)
    }

    @Test
    fun `충돌 후 움직이는 동안은 문을 열지 않음`() {
        engine.onSpeed(20f, 0)
        engine.onAirbagDeployed(true, 0)
        assertEquals(Phase.POST_CRASH, engine.phase)
        assertFalse(Action.UnlockAllDoors in actions)
        engine.onSpeed(4f, 1000)
        engine.tick(2500)
        assertFalse("정지 2초 미만", Action.UnlockAllDoors in actions)
        engine.tick(3000)
        assertTrue(Action.UnlockAllDoors in actions)
        assertTrue(Action.OpenWindows(100) in actions)
        assertTrue(Action.CabinLight(true) in actions)
    }

    @Test
    fun `대피 안내는 20초 간격 최대 3회`() {
        engine.onSpeed(0f, 0)
        engine.onAirbagDeployed(true, 0)
        for (t in 1_000L..120_000L step 1_000) engine.tick(t)
        assertEquals(3, taken<Action.Speak>().count { "대피" in it.text })
        assertEquals(1, actions.count { it == Action.UnlockAllDoors })
    }

    @Test
    fun `에어백 신호 중복은 무시`() {
        engine.onSpeed(0f, 0)
        engine.onAirbagDeployed(true, 0)
        engine.onAirbagDeployed(true, 100)
        engine.tick(2_500)
        engine.onAirbagDeployed(true, 3_000)
        engine.tick(5_000)
        assertEquals(1, taken<Action.IncidentReport>().size)
        assertEquals(1, taken<Action.PhaseChanged>().count { it.phase == Phase.POST_CRASH })
    }

    @Test
    fun `고속 순항 중 전방 정체 1500m 안이면 예고 1회`() {
        drive(0L to 100f, 1000L to 100f)
        engine.onCongestionAhead(2_500, 1000)
        assertTrue("예고 거리 밖", actions.isEmpty())
        engine.onCongestionAhead(1_240, 2000)
        engine.onCongestionAhead(1_000, 3000)
        assertEquals(Phase.CRUISING, engine.phase)
        assertEquals(1, actions.count { it == Action.RearLights(true) })
        assertEquals(1, taken<Action.Speak>().count { "1.2킬로미터 앞 정체" in it.text })
    }

    @Test
    fun `저속이거나 정체가 사라지면 예고하지 않거나 해제`() {
        drive(0L to 50f)
        engine.onCongestionAhead(800, 0)
        assertTrue("저속 주행은 예고 안 함", actions.isEmpty())
        drive(1000L to 100f, 2000L to 100f)
        engine.onCongestionAhead(800, 2000)
        assertTrue(engine.jamAheadWarned)
        engine.onCongestionAhead(null, 3000)
        assertFalse(engine.jamAheadWarned)
        assertTrue(Action.RearLights(false) in actions)
    }

    @Test
    fun `예고 후 급감속하면 정체 꼬리 경고로 이어짐`() {
        drive(0L to 100f, 1000L to 100f)
        engine.onCongestionAhead(1_000, 1000)
        drive(2000L to 80f, 3000L to 50f, 4000L to 35f)
        assertEquals(Phase.TAIL_ENTRY, engine.phase)
        engine.onCongestionAhead(null, 4500)
        assertFalse("경고 단계에서는 내비 정보로 조명을 끄지 않음", Action.RearLights(false) in actions)
    }

    @Test
    fun `봉평터널 시나리오 전체 흐름`() {
        DemoScenario.bongpyeong().forEach { DemoScenario.apply(engine, it) }
        val phases = taken<Action.PhaseChanged>().map { it.phase }
        assertEquals(listOf(Phase.TAIL_ENTRY, Phase.STOPPED_IN_QUEUE, Phase.POST_CRASH), phases)
        assertTrue("내비 정체 예고가 급감속보다 먼저", taken<Action.Speak>().first().text.startsWith("약 1.2킬로미터 앞 정체"))
        assertTrue(taken<Action.Speak>().any { "뒷좌석 오른쪽 좌석 안전벨트" in it.text })
        // 바퀴 조향은 운전자 안내가 아니라 충돌 확실 시 자동(시뮬레이션) — 시나리오에 운전자 조향 입력 없음
        assertTrue(taken<Action.Speak>().none { "준비 완료" in it.text || "핸들" in it.text })
        val report = taken<Action.IncidentReport>().single()
        assertEquals(4, report.occupants)
        assertEquals("경고 후 착용했으므로 미착용 0명", 0, report.unbelted)
        // 문 개방은 추돌로 밀린 차가 멈춘 뒤(23.5초) 2초 정지 확인 후
        assertTrue(report.atMs >= 25_500)
        // 화면 표시용 읽기
        assertTrue(engine.doorsReleased)
        assertEquals(true, engine.beltOf(Seat.REAR_RIGHT))
        assertEquals(200, engine.jamAheadM)
    }
}
