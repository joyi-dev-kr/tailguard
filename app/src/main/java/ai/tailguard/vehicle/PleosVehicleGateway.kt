package ai.tailguard.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.tailguard.core.Action
import ai.tailguard.core.Seat
import ai.tailguard.core.TailGuardEngine
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pleos Vehicle SDK ↔ TailGuardEngine 연결.
 *  - 입력: 조향각, 좌석 착석·벨트, 에어백 전개 → engine
 *  - 출력: engine Action → 조명·도어·창문·좌석 제어
 * 모든 SDK 호출은 비동기(onComplete/onFailed). 실패는 log 로 남기고 다른 동작은 계속한다.
 */
class PleosVehicleGateway(
    context: Context,
    private val engine: () -> TailGuardEngine,
    private val log: (String) -> Unit,
    /** 제어 결과 (화면 상태 열 표시용) */
    private val onResult: (Device, Boolean) -> Unit = { _, _ -> },
    /** false 면 차량 입력을 엔진에 넘기지 않음 (시연 재생 중 실제 신호가 섞이지 않도록) */
    private val acceptInput: () -> Boolean = { true },
) {
    private val vehicle = Vehicle(context)
    private val main = Handler(Looper.getMainLooper())
    private val clock: () -> Long = { System.currentTimeMillis() }

    private val seatMap = mapOf(
        Seat.DRIVER to SeatValue.ROW_1_LEFT,
        Seat.PASSENGER to SeatValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to SeatValue.ROW_2_LEFT,
        Seat.REAR_CENTER to SeatValue.ROW_2_CENTER,
        Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )
    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    private val windows = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)

    private val listeners = mutableListOf<() -> Unit>()

    fun start() {
        vehicle.initialize()
        // 차종별 지원 범위가 달라 한 항목 실패가 나머지 등록을 막지 않도록 개별 처리
        safely("조향각") { registerSteering() }
        seatMap.forEach { (seat, value) -> safely("${seat.label} 좌석") { registerSeat(seat, value) } }
        // 1열 에어백 2곳 감시 (차종별 지원 좌석은 checkAirbagDeployedCapabilities 로 확인 가능)
        listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT).forEach { v -> safely("에어백 $v") { registerAirbag(v) } }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("$what 등록 실패: ${e.message}") }
    }

    fun stop() {
        listeners.forEach { runCatching { it() } }
        listeners.clear()
        vehicle.release()
    }

    // ---------- 입력 ----------

    private fun registerSteering() {
        val sw = vehicle.getSteeringWheel()
        val l = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(value: Float?) {
                value ?: return
                input { engine().onSteeringAngle(value, clock()) }
            }
            override fun onFailed(e: Exception) = log("조향각 수신 실패: ${e.message}")
        }
        sw.registerSteeringWheelAngle(l)
        listeners += { sw.unregisterSteeringWheelAngle(l) }
    }

    private fun registerSeat(seat: Seat, value: SeatValue) {
        val s = vehicle.getSeat()
        val area = SeatArea(value)
        val occ = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) {
                value ?: return
                input { engine().onSeatOccupancy(seat, value) }
            }
            override fun onFailed(e: Exception) = log("${seat.label} 착석 수신 실패: ${e.message}")
        }
        val belt = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) {
                value ?: return
                input { engine().onSeatBelt(seat, value) }
            }
            override fun onFailed(e: Exception) = log("${seat.label} 벨트 수신 실패: ${e.message}")
        }
        s.registerSeatOccupancy(area, occ)
        s.registerSeatBeltBuckledState(area, belt)
        listeners += { s.unregisterSeatOccupancy(area, occ) }
        listeners += { s.unregisterSeatBeltBuckledState(area, belt) }
    }

    private fun registerAirbag(value: SeatValue) {
        val safety = vehicle.getSafety()
        val area = SeatArea(value)
        val l = object : SeatAirbagDeployedListener {
            override fun onSeatAirbagDeployUpdated(value: Boolean?) {
                if (value == true) input { engine().onAirbagDeployed(true, clock()) }
            }
            override fun onFailed(e: Exception) = log("에어백 상태 수신 실패: ${e.message}")
        }
        safety.registerSeatAirbagDeployed(area, l)
        listeners += { safety.unregisterSeatAirbagDeployed(area, l) }
    }

    // ---------- 출력 ----------

    fun execute(action: Action) = safely("동작 $action") {
        when (action) {
            is Action.RearLights -> {
                val sw = if (action.on) LightSwitch.ON else LightSwitch.AUTOMATIC
                val light = vehicle.getLight()
                light.setTailLampLightSwitch(sw, { log("후미등 → $it"); ok(Device.TAIL_LAMP) }, { fail("후미등", it, Device.TAIL_LAMP) })
                val fog = if (action.on) LightSwitch.ON else LightSwitch.OFF
                light.setRearFogLightSwitch(fog, { log("후방 안개등 → $it"); ok(Device.REAR_FOG) }, { fail("후방 안개등", it, Device.REAR_FOG) })
            }
            is Action.CabinLight -> vehicle.getLight().setCabinLightSwitch(
                if (action.on) LightSwitch.ON else LightSwitch.AUTOMATIC,
                { log("실내등 → $it"); ok(Device.CABIN_LIGHT) }, { fail("실내등", it, Device.CABIN_LIGHT) },
            )
            Action.UnlockAllDoors -> unlockDoors()
            is Action.OpenWindows -> openWindows(action.percent)
            is Action.BackrestUpright -> action.seats.mapNotNull { seatMap[it] }.forEach(::raiseBackrest)
            else -> Unit // Speak·Guidance·PhaseChanged·IncidentReport 는 UI 계층에서 처리
        }
    }

    /** 차종마다 도어 영역 구성이 달라(에뮬레이터는 좌석별 ID 미지원) 지원 영역을 조회해 그대로 해제한다. */
    private fun unlockDoors() {
        val door = vehicle.getDoor()
        door.checkDoorLockCapabilities({ _, areas: List<DoorArea> ->
            // 탑승 도어만 (에뮬레이터는 HOOD 도 잠금 영역으로 보고함 → 후드는 열지 않음)
            val targets = areas.filter { a -> a.values.any { it in doors } }.ifEmpty { doors.map { DoorArea(it) } }
            log("도어 잠금 지원 영역 ${targets.size}곳")
            targets.forEach { a ->
                door.setDoorLock(a, false, { area, locked -> log("도어 ${area.values.joinToString()} 잠금=$locked"); ok(Device.DOORS) }, { fail("도어 ${a.values.joinToString()}", it, Device.DOORS) })
            }
        }, { fail("도어 영역 조회", it, Device.DOORS) })
    }

    private fun openWindows(percent: Int) {
        val win = vehicle.getWindow()
        win.checkWindowPositionCapabilities({ _, bounds: Map<WindowArea, AreaBounds<Int>> ->
            // 측면 창만 (앞·뒤 유리, 선루프 제외)
            val side = bounds.filterKeys { k -> k.values.any { it in windows } }
            if (side.isEmpty()) { log("창문 위치 지원 영역 없음"); post { onResult(Device.WINDOWS, false) } }
            side.forEach { (area, b) ->
                // AAOS WINDOW_POS 관례: min = 완전 열림, max = 닫힘. percent=100 → min (실차 확인 필요)
                val target = b.maxValue - (b.maxValue - b.minValue) * percent / 100
                win.setWindowPosition(area, target, { a, pos -> log("창문 ${a.values.joinToString()} → $pos"); ok(Device.WINDOWS) }, { fail("창문 ${area.values.joinToString()}", it, Device.WINDOWS) })
            }
        }, { fail("창문 범위 조회", it, Device.WINDOWS) })
    }

    /** 등받이를 앞으로 1.5초 이동 후 정지 (FORWARD 가 '세우기' 방향인지 실차 확인 필요). */
    private fun raiseBackrest(value: SeatValue) {
        val seat = vehicle.getSeat()
        val area = SeatArea(value)
        seat.moveSeatBackrestAngle(area, MoveControl.FORWARD, { _, _ -> log("등받이 $value 세우는 중") }, { fail("등받이 $value", it) })
        main.postDelayed({
            seat.moveSeatBackrestAngle(area, MoveControl.STOP, { _, _ -> }, { fail("등받이 정지 $value", it) })
        }, 1_500)
    }

    private fun fail(what: String, e: Exception?, device: Device? = null) {
        log("$what 제어 실패: ${e?.message}")
        if (device != null) post { onResult(device, false) }
    }

    private fun ok(device: Device) = post { onResult(device, true) }

    private fun post(block: () -> Unit) = main.post(block)

    private fun input(block: () -> Unit) = main.post { if (acceptInput()) block() }
}

/** 차량 제어 대상 (화면 상태 표시용). */
enum class Device { TAIL_LAMP, REAR_FOG, CABIN_LIGHT, DOORS, WINDOWS }
