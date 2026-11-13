package ai.tailguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearGateTest {

    @Test
    fun `도로 구분 - 주소와 제한속도`() {
        assertEquals(RoadType.HIGHWAY, RoadClassifier.classify(null, "강원 평창군 봉평면 영동고속도로"))
        assertEquals(RoadType.HIGHWAY, RoadClassifier.classify(100, "경기 성남시"))
        assertEquals(RoadType.HIGHWAY, RoadClassifier.classify(80, null))
        assertEquals(RoadType.CITY, RoadClassifier.classify(50, "서울 강남구 테헤란로"))
        assertEquals(RoadType.UNKNOWN, RoadClassifier.classify(null, null))
    }

    @Test
    fun `설정 끄면 어디서든 꺼짐`() {
        assertFalse(RearGate.evaluate(RearSettings(enabled = false), RoadType.HIGHWAY, inJam = true).active)
    }

    @Test
    fun `시내는 기본 꺼짐, 시내 감지 켜면 동작`() {
        assertFalse(RearGate.evaluate(RearSettings(), RoadType.CITY, inJam = true).active)
        assertTrue(RearGate.evaluate(RearSettings(inCity = true), RoadType.CITY, inJam = true).active)
        assertTrue(RearGate.evaluate(RearSettings(), RoadType.HIGHWAY, inJam = false).active)
    }

    @Test
    fun `도로 정보가 없으면 막지 않음`() {
        assertTrue(RearGate.evaluate(RearSettings(), RoadType.UNKNOWN, inJam = false).active)
    }

    @Test
    fun `정체 구간에서만 설정`() {
        val s = RearSettings(jamOnly = true)
        assertFalse(RearGate.evaluate(s, RoadType.HIGHWAY, inJam = false).active)
        assertTrue(RearGate.evaluate(s, RoadType.HIGHWAY, inJam = true).active)
    }

    @Test
    fun `정체 구간 판단 - 내비 정체 1_5km 안 또는 정체 꼬리 단계`() {
        val engine = TailGuardEngine(sink = {})
        assertFalse(RearGate.inJam(engine))
        engine.onSpeed(100f, 0)
        engine.onCongestionAhead(1_200, 0)
        assertTrue(RearGate.inJam(engine))
    }
}
