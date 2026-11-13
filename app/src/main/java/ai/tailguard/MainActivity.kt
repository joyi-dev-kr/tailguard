package ai.tailguard

import ai.tailguard.core.Action
import ai.tailguard.core.DemoScenario
import ai.tailguard.core.RearApproachJudge
import ai.tailguard.core.RearGate
import ai.tailguard.core.RearSettings
import ai.tailguard.core.RoadClassifier
import ai.tailguard.core.RoadType
import ai.tailguard.core.Seat
import ai.tailguard.core.TailGuardEngine
import ai.tailguard.ui.DashState
import ai.tailguard.ui.DashboardView
import ai.tailguard.vehicle.CarSpeedSource
import ai.tailguard.vehicle.Device
import ai.tailguard.vehicle.NaviTrafficSource
import ai.tailguard.vehicle.PleosVehicleGateway
import ai.tailguard.vehicle.DemoClipFrames
import ai.tailguard.vehicle.RearVehicleDetector
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * 운전 중 조작이 필요 없는 단일 화면 ([DashboardView]): 단계 배너, 단계별 안내 카드, 차량 상태 열.
 * [▶ 시연] 은 봉평터널 상황을 재현하는 시나리오를 실시간 속도로 재생하고, [로그] 는 동작 기록을 겹쳐 보여준다.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val dash = DashState()

    private var speaker: Speaker? = null
    private var gateway: PleosVehicleGateway? = null
    private var speed: CarSpeedSource? = null
    private var traffic: NaviTrafficSource? = null
    private lateinit var engine: TailGuardEngine
    private var rear = RearApproachJudge(sink = { })

    /** 후방 영상 인식은 별도 스레드 (메인 화면을 막지 않도록) */
    private val rearThread = HandlerThread("rear").also { it.start() }
    private val rearWorker = Handler(rearThread.looper)
    private var detector: RearVehicleDetector? = null
    private val clip by lazy { DemoClipFrames(this, ::log) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!dash.demoRunning) engine.tick(System.currentTimeMillis())
            rear.egoKmh = engine.speedKmh
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = newEngine()
        rear = RearApproachJudge(sink = ::onAction)
        dash.settings = loadSettings()
        setContentView(DashboardView(this, { engine }, { rear }, dash, ::runDemo, ::onSettingsChanged))
        // 차량 인식 모델과 후방 시연 영상은 미리 준비 (에뮬레이터에서 수 초 걸림)
        rearWorker.post {
            detector = RearVehicleDetector(this, ::log).also { if (it.ready) log("후방 차량 인식 모델 준비") }
            clip.load()
            // 검증 영상에 카메라 화각이 적혀 있으면 그 화각으로 판정기를 다시 만든다
            clip.hfovDeg?.let { h -> main.post { rear = RearApproachJudge(ai.tailguard.core.RearJudgeConfig(cameraHfovDeg = h), ::onAction) } }
            main.post { dash.rearSourceLabel = clip.sourceLabel }
        }
        speaker = Speaker(this, ::log).also { it.start() }
        gateway = runCatching { PleosVehicleGateway(this, { engine }, ::log, ::onControlResult, { !dash.demoRunning }).also { it.start() } }
            .onFailure { log("Pleos Vehicle 연결 실패: ${it.message}") }
            .getOrNull()
        speed = CarSpeedSource(this, { kmh, t -> main.post { if (!dash.demoRunning) engine.onSpeed(kmh, t) } }, ::log)
            .also { it.start() }
        traffic = runCatching {
            NaviTrafficSource(
                this, { m, t -> if (!dash.demoRunning) engine.onCongestionAhead(m, t) }, ::log,
                onRoad = { limit, addr ->
                    val road = RoadClassifier.classify(limit, addr)
                    if (road != dash.liveRoad) log("도로 구분: ${road.label} (제한 ${limit ?: "-"}km/h)")
                    dash.liveRoad = road
                },
            ).also { it.start() }
        }.onFailure { log("내비 연결 실패: ${it.message}") }.getOrNull()
        main.post(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speed?.stop()
        traffic?.stop()
        gateway?.stop()
        speaker?.release()
        rearWorker.post { detector?.close() }
        rearThread.quitSafely()
        super.onDestroy()
    }

    private fun newEngine() = TailGuardEngine(sink = ::onAction)

    private fun onAction(a: Action) {
        when (a) {
            is Action.PhaseChanged -> log("■ ${a.phase.label}")
            is Action.Speak -> {
                log("🔊 ${a.text}")
                speaker?.speak(a.text, a.urgent)
            }
            is Action.Guidance -> dash.guidance = a.text
            is Action.RearApproachWarning -> log("⚠ 후방 ${a.kind.label} 급접근 — 충돌까지 %.1f초".format(a.ttcS))
            is Action.CollisionCertain -> {
                log("■ 충돌 확실 — 충돌까지 %.1f초, 뒤차 필요 감속도 %.1f m/s²".format(a.ttcS, a.requiredDecel))
                val riders = Seat.entries.filter { engine.isOccupied(it) }.toSet()
                gateway?.execute(Action.BackrestUpright(riders))
            }
            is Action.WheelTurnSim -> {
                dash.wheelSimDeg = a.deg
                dash.wheelSimAtMs = System.currentTimeMillis()
                log("⚙ 바퀴 ${a.deg.toInt()}° 급속 조향 (시뮬레이션 — 조향 제어는 제조사 영역)")
            }
            Action.RearApproachCleared -> log("후방 접근 해제")
            is Action.IncidentReport -> {
                dash.incident = DashState.Incident(a.atMs, a.occupants, a.unbelted)
                log("📋 사고 기록: 탑승 ${a.occupants}명, 벨트 미착용 ${a.unbelted}명")
            }
            else -> {
                when (a) {
                    is Action.RearLights -> {
                        dash.rearLightsRequested = a.on
                        dash.results.remove(Device.TAIL_LAMP); dash.results.remove(Device.REAR_FOG)
                    }
                    is Action.CabinLight -> { dash.cabinRequested = a.on; dash.results.remove(Device.CABIN_LIGHT) }
                    Action.UnlockAllDoors -> dash.doorsRequested = true
                    is Action.OpenWindows -> dash.windowsRequested = true
                    else -> Unit
                }
                log("⚙ $a")
                gateway?.execute(a)
            }
        }
    }

    /** 도어·창문은 여러 곳 중 한 곳이라도 성공하면 성공으로 표시 */
    private fun onControlResult(d: Device, ok: Boolean) {
        dash.results[d] = ok || dash.results[d] == true
    }

    // ---------- 시연 ----------

    private fun runDemo() {
        if (dash.demoRunning) return
        dash.demoRunning = true
        dash.reset()
        engine = newEngine()
        rear.reset()
        dash.guidance = "시연: 영동고속도로 봉평터널 입구 상황"
        dash.demoRoad = RoadType.HIGHWAY // 시연 장소(영동고속도로)의 도로 구분
        log("▶ 시연 시작")
        val base = System.currentTimeMillis()
        val events = DemoScenario.bongpyeong()
        // 추돌 전 구간은 바로 예약, 추돌 이후는 후방 영상 인식이 끝난 뒤 이어서 (에뮬레이터는 인식이 느려 영상이 길어짐)
        val (before, after) = events.partition { it.atMs < DemoScenario.CRASH_AT_MS }
        before.forEach { e -> main.postDelayed({ DemoScenario.apply(engine, e.shift(base)) }, e.atMs) }
        main.postDelayed({
            playRearClip(base) {
                // 추돌 시각을 '지금'으로 맞춰 나머지 사건을 이어서 재생
                val base2 = System.currentTimeMillis() + 300 - DemoScenario.CRASH_AT_MS
                after.forEach { e -> main.postDelayed({ DemoScenario.apply(engine, e.shift(base2)) }, maxOf(0L, base2 + e.atMs - System.currentTimeMillis())) }
                main.postDelayed({ dash.demoRunning = false; dash.demoRoad = null; log("■ 시연 종료") },
                    maxOf(0L, base2 + after.maxOf { it.atMs } + 500 - System.currentTimeMillis()))
            }
        }, REAR_START_MS)
    }

    /**
     * 후방 시연 영상을 정차 중 구간에 재생하며 프레임마다 차량 인식 → 판정기에 넣는다.
     * 실차에서는 이 자리에 후방 카메라 프레임이 들어온다 (영상 공급원만 다르고 인식·판정 경로는 같음).
     * 시각은 영상 속 시각(프레임 번호 × 0.1초)을 쓴다 — 기기 성능과 무관하게 같은 판정이 나오도록.
     * 에뮬레이터는 인식이 느려 화면 재생이 실제보다 느리다 (차량 기기의 NPU·GPU 에서는 실시간).
     */
    private fun playRearClip(base: Long, onDone: () -> Unit) {
        val frames = clip.frames
        val det = detector
        if (frames.isEmpty() || det == null || !det.ready) { log("후방 시연 영상·인식 모델 준비 전 — 후방 감지 생략"); onDone(); return }
        val step = 1000L / DemoClipFrames.FPS
        rearWorker.post {
            // 검증 영상(실제 사고)은 모든 프레임을, 시연 영상은 2프레임마다 인식
            val stride = if (clip.sourceLabel.startsWith("검증")) 1 else REAR_STRIDE
            for (i in frames.indices step stride) {
                val frame = frames[i]
                val t = base + REAR_START_MS + i * step
                val r = det.detect(frame)
                main.post {
                    if (!dash.demoRunning) return@post
                    dash.rearFrame = frame
                    dash.rearBox = r.box
                    dash.rearLabel = r.label
                    feedRear(r.detection, t)
                }
                if (!dash.demoRunning) break
            }
            main.postDelayed({
                dash.rearFrame = null; dash.rearBox = null; dash.rearLabel = null
                onDone()
            }, 600)
        }
    }

    private fun DemoScenario.Event.shift(base: Long): DemoScenario.Event = when (this) {
        is DemoScenario.Event.Speed -> copy(atMs = base + atMs)
        is DemoScenario.Event.Steering -> copy(atMs = base + atMs)
        is DemoScenario.Event.Belt -> copy(atMs = base + atMs)
        is DemoScenario.Event.Occupancy -> copy(atMs = base + atMs)
        is DemoScenario.Event.Airbag -> copy(atMs = base + atMs)
        is DemoScenario.Event.JamAhead -> copy(atMs = base + atMs)
        is DemoScenario.Event.Tick -> copy(atMs = base + atMs)
    }

    /** 설정·도로 구분·정체 구간 조건을 통과할 때만 판정기에 넣는다. 꺼지면 진행 중이던 경고도 해제. */
    private fun feedRear(det: ai.tailguard.core.RearDetection?, t: Long) {
        val gate = RearGate.evaluate(dash.settings, dash.road, RearGate.inJam(engine))
        if (gate.reason != dash.rearGate.reason) log("후방 감지: ${gate.reason}")
        dash.rearGate = gate
        if (!gate.active) { rear.reset(); return }
        rear.egoKmh = engine.speedKmh
        rear.onFrame(det, t)
        android.util.Log.d("TailGuardRear", "det=${det?.kind} w=${det?.widthFrac?.let { "%.3f".format(it) }} " +
            "score=${det?.score?.let { "%.2f".format(it) }} level=${rear.level} est=${rear.last?.let { "ttc=%.2f d=%.1f v=%.1f a=%.1f".format(it.ttcS, it.distM, it.closingMps, it.requiredDecel) }}")
    }

    // ---------- 설정 ----------

    private fun loadSettings(): RearSettings {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = RearSettings()
        return RearSettings(
            enabled = sp.getBoolean("rear_enabled", d.enabled),
            inCity = sp.getBoolean("rear_in_city", d.inCity),
            jamOnly = sp.getBoolean("rear_jam_only", d.jamOnly),
        )
    }

    private fun onSettingsChanged(s: RearSettings) {
        dash.settings = s
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("rear_enabled", s.enabled).putBoolean("rear_in_city", s.inCity).putBoolean("rear_jam_only", s.jamOnly)
            .apply()
        log("설정: 후방 감지 ${onOff(s.enabled)} · 시내 ${onOff(s.inCity)} · 정체 구간에서만 ${onOff(s.jamOnly)}")
        val gate = RearGate.evaluate(s, dash.road, RearGate.inJam(engine))
        dash.rearGate = gate
        if (!gate.active) rear.reset()
    }

    private fun onOff(b: Boolean) = if (b) "켬" else "끔"

    companion object {
        private const val PREFS = "tailguard_settings"
        /** 시연 시나리오에서 정차 후 후방 영상이 시작되는 시각 (추돌 22초 직전) */
        private const val REAR_START_MS = 17_500L
        /** 시연 영상 프레임 간격 (2 = 5프레임/초로 인식) */
        private const val REAR_STRIDE = 2
    }

    // ---------- 로그 ----------

    private val shownFailures = mutableSetOf<String>()

    private fun log(msg: String) {
        android.util.Log.i("TailGuard", msg)
        main.post {
            // 같은 실패(권한 없음 등)는 한 번만 기록
            if ("실패" in msg && !shownFailures.add(msg.substringAfter(": "))) return@post
            dash.logs.addLast(msg)
            while (dash.logs.size > 200) dash.logs.removeFirst()
        }
    }
}
