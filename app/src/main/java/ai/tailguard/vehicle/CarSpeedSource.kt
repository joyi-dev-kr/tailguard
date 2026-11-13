package ai.tailguard.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/**
 * 차속은 Pleos SDK 가 아니라 AAOS 표준 Car API 로 받는다 (Pleos "Standard First" 정책).
 * PERF_VEHICLE_SPEED 단위는 m/s → km/h 변환.
 */
class CarSpeedSource(
    private val context: Context,
    private val onSpeedKmh: (Float, Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private var car: Car? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null

    fun start(): Boolean {
        if (!context.packageManager.hasSystemFeature("android.hardware.type.automotive")) {
            log("자동차 OS 가 아님 → 차속 미사용 (시연 모드만 가능)")
            return false
        }
        return runCatching {
            val c = Car.createCar(context)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val ms = (value.value as? Float) ?: return
                    onSpeedKmh(ms * 3.6f, System.currentTimeMillis())
                }
                override fun onErrorEvent(propId: Int, zone: Int) = log("차속 오류 prop=$propId")
            }
            callback = cb
            pm.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
            log("차속 수신 시작")
            true
        }.getOrElse { log("차속 수신 실패: ${it.message}"); false }
    }

    fun stop() {
        runCatching {
            val c = car ?: return
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            callback?.let { pm.unregisterCallback(it) }
            c.disconnect()
        }
        car = null
    }
}
