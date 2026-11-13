package ai.tailguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class RearApproachJudgeTest {

    private val actions = mutableListOf<Action>()
    private val judge = RearApproachJudge(sink = { actions += it })
    private inline fun <reified T : Action> taken() = actions.filterIsInstance<T>()

    /** 카메라 화각으로 실제 거리를 박스 폭 비율로 바꾼다 (판정기와 같은 모델) */
    private fun widthAt(distM: Float, kind: VehicleKind): Float {
        val focal = 0.5 / tan(Math.toRadians(judge.cfg.cameraHfovDeg / 2.0))
        return (kind.widthM * focal / distM).toFloat()
    }

    /** 내 차 정지, 뒤차가 startM 거리에서 일정 속도 kmh 로 다가오는 장면을 100ms 간격으로 넣는다 */
    private fun approach(kind: VehicleKind, startM: Float, kmh: Float, untilM: Float, t0: Long = 0L): Long {
        val v = kmh / 3.6f
        var t = t0
        var d = startM
        while (d > untilM) {
            judge.onFrame(RearDetection(kind, widthAt(d, kind), 0.8f), t)
            t += 100; d -= v * 0.1f
        }
        return t
    }

    @Test
    fun `대형차가 고속으로 다가오면 경고 후 충돌 확실`() {
        approach(VehicleKind.BUS, startM = 60f, kmh = 80f, untilM = 3f)
        assertEquals(1, taken<Action.RearApproachWarning>().size)
        assertEquals(1, taken<Action.CollisionCertain>().size)
        assertEquals(45f, taken<Action.WheelTurnSim>().single().deg)
        assertTrue(taken<Action.Speak>().any { it.urgent && "충격에 대비" in it.text })
        assertTrue(Action.RearLights(true) in actions)
        assertEquals(RearLevel.CERTAIN, judge.level)
        // 경고가 확실 판정보다 먼저
        assertTrue(actions.indexOfFirst { it is Action.RearApproachWarning } < actions.indexOfFirst { it is Action.CollisionCertain })
    }

    @Test
    fun `남은 시간 추정이 실제와 가깝다`() {
        approach(VehicleKind.TRUCK, startM = 40f, kmh = 72f, untilM = 30f) // 20 m/s, 30 m → 1.5초
        val est = assertNotNullAndGet()
        assertTrue("ttc=${est.ttcS}", est.ttcS in 1.2f..1.9f)
        assertTrue("dist=${est.distM}", est.distM in 27f..34f)
        assertTrue("v=${est.closingMps}", est.closingMps in 16f..24f)
    }

    private fun assertNotNullAndGet(): RearEstimate = judge.last.also { assertNotNull(it) }!!

    @Test
    fun `천천히 다가와 멈추는 차는 경고하지 않음`() {
        // 10 km/h 로 다가오다 5 m 에서 멈춤
        val t = approach(VehicleKind.BUS, startM = 20f, kmh = 10f, untilM = 5f)
        for (i in 0 until 20) judge.onFrame(RearDetection(VehicleKind.BUS, widthAt(5f, VehicleKind.BUS), 0.8f), t + i * 100L)
        assertTrue(taken<Action.RearApproachWarning>().isEmpty())
        assertTrue(taken<Action.CollisionCertain>().isEmpty())
    }

    @Test
    fun `같은 트럭이 프레임마다 버스·트럭·승용차로 바뀌어 불려도 추적 유지`() {
        val labels = listOf(VehicleKind.TRUCK, VehicleKind.BUS, VehicleKind.CAR, VehicleKind.TRUCK, VehicleKind.BUS)
        val v = 70f / 3.6f
        var t = 0L; var d = 50f; var i = 0
        while (d > 3f) {
            val k = labels[i++ % labels.size]
            judge.onFrame(RearDetection(k, widthAt(d, VehicleKind.TRUCK), 0.6f), t)
            t += 100; d -= v * 0.1f
        }
        assertEquals(1, taken<Action.CollisionCertain>().size)
    }

    @Test
    fun `저속 추돌은 반응하지 않음 - 대형 트럭 15km_h 로 들이받음`() {
        approach(VehicleKind.TRUCK, startM = 15f, kmh = 15f, untilM = 0.3f)
        assertTrue(taken<Action.RearApproachWarning>().isEmpty())
        assertTrue(taken<Action.CollisionCertain>().isEmpty())
        assertTrue(taken<Action.WheelTurnSim>().isEmpty())
    }

    @Test
    fun `차간거리를 좁혀 붙는 대형차는 경고하지 않음 - 25km_h 로 다가와 2m 에서 멈춤`() {
        val t = approach(VehicleKind.BUS, startM = 30f, kmh = 25f, untilM = 2f)
        for (i in 0 until 15) judge.onFrame(RearDetection(VehicleKind.BUS, widthAt(2f, VehicleKind.BUS), 0.8f), t + i * 100L)
        assertTrue(taken<Action.RearApproachWarning>().isEmpty())
        assertTrue(taken<Action.WheelTurnSim>().isEmpty())
    }

    @Test
    fun `승용차는 빠르게 다가와도 대형차 경고를 하지 않음`() {
        approach(VehicleKind.CAR, startM = 50f, kmh = 70f, untilM = 3f)
        assertTrue(taken<Action.RearApproachWarning>().isEmpty())
        assertTrue(taken<Action.WheelTurnSim>().isEmpty())
    }

    @Test
    fun `내 차가 달리는 중이면 판정하지 않음`() {
        judge.egoKmh = 90f
        approach(VehicleKind.TRUCK, startM = 60f, kmh = 80f, untilM = 3f)
        assertTrue(taken<Action.RearApproachWarning>().isEmpty())
    }

    @Test
    fun `한 프레임 튀는 검출은 무시`() {
        var t = 0L
        for (i in 0 until 10) { judge.onFrame(RearDetection(VehicleKind.BUS, widthAt(50f, VehicleKind.BUS), 0.8f), t); t += 100 }
        judge.onFrame(RearDetection(VehicleKind.BUS, widthAt(10f, VehicleKind.BUS), 0.8f), t) // 갑자기 커진 오검출 1장
        t += 100
        judge.onFrame(RearDetection(VehicleKind.BUS, widthAt(50f, VehicleKind.BUS), 0.8f), t)
        assertTrue(taken<Action.CollisionCertain>().isEmpty())
    }

    @Test
    fun `차가 사라지면 경고 해제`() {
        val t = approach(VehicleKind.BUS, startM = 60f, kmh = 60f, untilM = 25f)
        assertEquals(RearLevel.WARN, judge.level)
        judge.onFrame(null, t + 900)
        assertEquals(RearLevel.NONE, judge.level)
        assertEquals(1, taken<Action.RearApproachCleared>().size)
    }
}
