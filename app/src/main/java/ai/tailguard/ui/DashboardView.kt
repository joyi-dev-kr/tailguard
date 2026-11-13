package ai.tailguard.ui

import ai.tailguard.core.Phase
import ai.tailguard.core.RearApproachJudge
import ai.tailguard.core.RearGate
import ai.tailguard.core.RearLevel
import ai.tailguard.core.RearSettings
import ai.tailguard.core.RoadType
import ai.tailguard.core.Seat
import ai.tailguard.core.Side
import ai.tailguard.core.TailGuardEngine
import ai.tailguard.vehicle.Device
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면 상태 중 엔진에 없는 것: 요청한 제어와 그 결과, 안내 문구, 사고 기록, 로그. */
class DashState {
    var guidance: String = "대기 중"
    var rearLightsRequested = false
    var cabinRequested = false
    var doorsRequested = false
    var windowsRequested = false
    /** 제어 결과: true = 성공, false = 실패·미지원, 없음 = 응답 대기 */
    val results = mutableMapOf<Device, Boolean>()
    var crashAtMs: Long? = null
    var incident: Incident? = null
    var demoRunning = false
    var showLog = false
    val logs = ArrayDeque<String>()

    // 후방 카메라 (시연: 후방 시연 영상) — 현재 프레임, 인식 박스(0~1 비율), 이름표
    var rearFrame: Bitmap? = null
    var rearBox: RectF? = null
    var rearLabel: String? = null
    /** 충돌 확실 시 바퀴 조향 시뮬레이션 목표 각도와 시작 시각 */
    var wheelSimDeg = 0f
    var wheelSimAtMs: Long? = null

    // 설정·도로 구분 (후방 감지를 켤지)
    var settings = RearSettings()
    var showSettings = false
    /** 내비 지도 위치로 판단한 현재 도로 */
    var liveRoad = RoadType.UNKNOWN
    /** 시연 중에는 시연 장소의 도로 구분을 쓴다 */
    var demoRoad: RoadType? = null
    val road: RoadType get() = demoRoad ?: liveRoad
    var rearGate = RearGate.Gate(true, "후방 감시 중")
    var rearSourceLabel = "시연: 재연 영상"

    data class Incident(val atMs: Long, val occupants: Int, val unbelted: Int)

    fun reset() {
        guidance = "대기 중"
        rearLightsRequested = false; cabinRequested = false; doorsRequested = false; windowsRequested = false
        results.clear(); crashAtMs = null; incident = null
        rearFrame = null; rearBox = null; rearLabel = null; wheelSimDeg = 0f; wheelSimAtMs = null
    }
}

/**
 * TailGuard 단일 화면 (운전 중 조작 불필요). 단계 배너 + 단계별 카드 + 오른쪽 차량 상태 열.
 * 기준 좌표 560×400 에 그리고 창 크기에 맞춰 비율 유지 확대한다.
 */
@SuppressLint("ViewConstructor")
class DashboardView(
    context: Context,
    private val engine: () -> TailGuardEngine,
    private val rear: () -> RearApproachJudge,
    private val state: DashState,
    private val onDemo: () -> Unit,
    private val onSettings: (RearSettings) -> Unit,
) : View(context) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mono = Typeface.MONOSPACE

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var now = 0L

    private val demoChip = RectF(268f, 7f, 340f, 29f)
    private val logChip = RectF(346f, 7f, 394f, 29f)
    private val settingsChip = RectF(400f, 7f, 456f, 29f)
    private val settingRows = List(3) { i -> RectF(40f, 132f + i * 70f, 520f, 188f + i * 70f) }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w / W0, h / H0)
        offX = (w - W0 * scale) / 2
        offY = (h - H0 * scale) / 2
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val x = (e.x - offX) / scale
            val y = (e.y - offY) / scale
            val row = if (state.showSettings) settingRows.indexOfFirst { it.contains(x, y) } else -1
            when {
                demoChip.contains(x, y) -> { state.showSettings = false; onDemo() }
                logChip.contains(x, y) -> { state.showLog = !state.showLog; state.showSettings = false }
                settingsChip.contains(x, y) -> { state.showSettings = !state.showSettings; state.showLog = false }
                row >= 0 -> {
                    val s = state.settings
                    onSettings(when (row) { 0 -> s.copy(enabled = !s.enabled); 1 -> s.copy(inCity = !s.inCity); else -> s.copy(jamOnly = !s.jamOnly) })
                }
            }
            invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        now = System.currentTimeMillis()
        c.drawColor(BG)
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        val eng = engine()
        drawTopBar(c, eng)
        drawBanner(c, eng)
        when (eng.phase) {
            Phase.CRUISING -> drawCruise(c, eng)
            Phase.TAIL_ENTRY -> drawWarn(c)
            Phase.STOPPED_IN_QUEUE -> drawPrep(c, eng)
            Phase.POST_CRASH -> drawCrash(c, eng)
        }
        // 아래 한 줄: 카드에 없는 정보만 (정체 예고 카드·대피 화면과 겹치는 문구는 생략)
        val footer = when {
            eng.phase == Phase.CRUISING && !eng.jamAheadWarned -> state.guidance
            eng.phase == Phase.STOPPED_IN_QUEUE && state.rearFrame == null -> "앞차와 차 한 대 간격 · 머리 헤드레스트"
            else -> null
        }
        if (footer != null) text(c, footer, BX, 378f, 16f, Color.rgb(230, 235, 240), true)
        line(c, 368f, 100f, 368f, 392f, Color.argb(20, 255, 255, 255), 1f)
        drawStatus(c, eng)
        if (state.showLog) drawLog(c)
        if (state.showSettings) drawSettings(c)
        c.restore()
    }

    // ---------- 상단 ----------

    private fun drawTopBar(c: Canvas, eng: TailGuardEngine) {
        text(c, "TailGuard", 16f, 18f, 17f, Color.rgb(232, 237, 242), true)
        text(c, "%.0f km/h".format(eng.speedKmh), 112f, 18f, 14f, Color.rgb(154, 166, 178))
        chip(c, demoChip, if (state.demoRunning) "시연 중" else "▶ 시연", state.demoRunning)
        chip(c, logChip, "로그", state.showLog)
        chip(c, settingsChip, "설정", state.showSettings)
        text(c, clock.format(Date(now)), 544f, 18f, 15f, Color.rgb(154, 166, 178), align = Paint.Align.RIGHT)
    }

    private fun chip(c: Canvas, r: RectF, label: String, active: Boolean) {
        rrect(c, r.left, r.top, r.width(), r.height(), 11f, if (active) Color.rgb(46, 74, 104) else Color.rgb(30, 36, 44))
        text(c, label, r.centerX(), r.centerY(), 13f, Color.rgb(200, 214, 228), true, Paint.Align.CENTER)
    }

    private fun drawBanner(c: Canvas, eng: TailGuardEngine) {
        val flash = eng.phase == Phase.POST_CRASH && !eng.doorsReleased && !blink(500)
        p.style = Paint.Style.FILL
        p.color = if (flash) Color.rgb(109, 13, 16) else phaseColor(eng.phase)
        c.drawRect(0f, 36f, W0, 86f, p)
        text(c, eng.phase.label, W0 / 2, 61f, 26f, Color.WHITE, true, Paint.Align.CENTER)
    }

    // ---------- 단계별 카드 ----------

    private fun drawCruise(c: Canvas, eng: TailGuardEngine) {
        val jam = eng.jamAheadM
        if (eng.jamAheadWarned && jam != null) {
            rrect(c, BX, BY, 336f, 132f, 12f, Color.rgb(58, 42, 8), Color.rgb(255, 178, 30))
            val km = km(jam)
            text(c, "$km km", BX + 18f, BY + 46f, 44f, Color.rgb(255, 197, 77), true)
            text(c, "앞 정체", BX + 18f + width("$km km", 44f, true) + 14f, BY + 50f, 26f, Color.rgb(255, 226, 160), true)
            text(c, "미리 감속 · 비상등을 켜 주세요", BX + 18f, BY + 102f, 19f, Color.rgb(255, 233, 194))
            routeBar(c, BX, BY + 178f, 326f, jam)
            text(c, "경로 교통정보 (내비)", BX, BY + 210f, 14f, Color.rgb(139, 150, 163))
        } else {
            text(c, if (eng.speedKmh >= 70f) "고속 순항 · 경로 교통정보 확인 중" else "경로 교통정보 확인 중", BX, BY + 16f, 19f, Color.rgb(207, 216, 225))
            routeBar(c, BX, BY + 80f, 326f, jam)
            text(c, if (jam != null) "경로상 정체 ${km(jam)} km 앞" else "경로상 정체 정보 없음", BX, BY + 120f, 17f, Color.rgb(139, 150, 163))
            text(c, "정체가 1.5 km 안으로 들어오면 미리 알려 드립니다", BX, BY + 200f, 14f, Color.rgb(110, 120, 132))
        }
    }

    private fun drawWarn(c: Canvas) {
        val cx = BX + 66f
        val cy = BY + 112f
        // 후방 경고 물결 (차 뒤쪽 = 아래)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f
        for (i in 0 until 3) {
            val r = 30f + i * 20f + (now % 700) / 700f * 20f
            p.color = Color.argb(130 - i * 38, 255, 70, 50)
            rect.set(cx - r, cy + 58f - r, cx + r, cy + 58f + r)
            c.drawArc(rect, 25f, 130f, false, p)
        }
        topCar(c, cx, cy, 1.25f, Color.rgb(215, 219, 224))
        text(c, "후방 추돌 주의", BX + 150f, BY + 50f, 28f, Color.rgb(255, 210, 168), true)
        text(c, "비상등을 켜 주세요", BX + 150f, BY + 104f, 20f, if (blink(600)) Color.rgb(255, 178, 30) else Color.rgb(168, 116, 42), true)
        if (state.rearLightsRequested) text(c, "후미등 · 안개등 점등", BX + 150f, BY + 146f, 17f, Color.rgb(201, 210, 219))
    }

    private fun drawPrep(c: Canvas, eng: TailGuardEngine) {
        seatMap(c, BX + 8f, BY, 1.25f, eng, doorsOpen = false)
        val unbelted = Seat.entries.filter { eng.isOccupied(it) && eng.beltOf(it) == false }
        if (unbelted.isNotEmpty()) {
            text(c, "벨트: " + unbelted.joinToString { it.label }, BX, BY + 236f, 15f, if (blink(500)) Color.rgb(255, 90, 79) else Color.rgb(160, 60, 55), true)
        }
        if (state.rearFrame != null || state.wheelSimAtMs != null) { drawRear(c); return }
        // 핸들 각도 게이지 (±180° → 반원)
        val gx = BX + 248f
        val gy = BY + 132f
        val r = 64f
        val right = eng.cfg.shoulderSide == Side.RIGHT
        rect.set(gx - r, gy - r, gx + r, gy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.strokeCap = Paint.Cap.BUTT
        p.color = Color.rgb(43, 49, 57); c.drawArc(rect, 180f, 180f, false, p)
        val zone = eng.cfg.escapeReadySteeringDeg / 180f * 90f
        p.color = Color.rgb(47, 143, 85)
        if (right) c.drawArc(rect, 270f + zone, 90f - zone, false, p) else c.drawArc(rect, 180f, 90f - zone, false, p)
        val rightDeg = eng.rightPositiveSteeringDeg
        val a = Math.toRadians((270f + rightDeg.coerceIn(-180f, 180f) / 180f * 90f).toDouble())
        line(c, gx, gy, gx + Math.cos(a).toFloat() * (r - 6f), gy + Math.sin(a).toFloat() * (r - 6f), Color.WHITE, 5f)
        val deg = Math.round(kotlin.math.abs(rightDeg))
        val dir = when { rightDeg > 0.5f -> "오른쪽 "; rightDeg < -0.5f -> "왼쪽 "; else -> "" }
        text(c, "핸들 $dir$deg°", gx, gy + 22f, 15f, Color.rgb(201, 210, 219), true, Paint.Align.CENTER)
        val ok = eng.escapeReady
        text(
            c, if (ok) "탈출 방향 준비 ✓" else "핸들 → ${eng.cfg.shoulderSide.label} 갓길", gx, gy - 92f, 18f,
            if (ok) Color.rgb(70, 211, 122) else Color.rgb(255, 210, 168), true, Paint.Align.CENTER,
        )
    }

    /** 정차 대비 화면 오른쪽: 후방 카메라 + 인식 박스 + 충돌까지 남은 시간 + 바퀴 각도(시뮬레이션) */
    private fun drawRear(c: Canvas) {
        val judge = rear()
        val x = BX + 132f
        val y = BY - 6f
        val w = 204f
        val h = w * 9f / 16f
        val certain = judge.level == RearLevel.CERTAIN
        val warn = judge.level == RearLevel.WARN
        val frame = state.rearFrame
        rrect(c, x - 2f, y - 2f, w + 4f, h + 4f, 6f, Color.rgb(8, 10, 13), if (certain) Color.rgb(255, 59, 48) else if (warn) Color.rgb(255, 178, 30) else Color.rgb(70, 80, 92))
        if (frame != null) {
            rect.set(x, y, x + w, y + h)
            p.isFilterBitmap = true
            c.drawBitmap(frame, null, rect, p)
            state.rearBox?.let { b ->
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
                p.color = if (certain) Color.rgb(255, 59, 48) else if (warn) Color.rgb(255, 178, 30) else Color.rgb(127, 209, 255)
                c.drawRect(x + b.left * w, y + b.top * h, x + b.right * w, y + b.bottom * h, p)
                state.rearLabel?.let { text(c, it, x + b.left * w + 3f, maxOf(y + 8f, y + b.top * h - 7f), 10f, p.color, true) }
            }
        }
        text(c, "후방 카메라", x + 5f, y + 9f, 9f, Color.rgb(220, 226, 232), true)
        text(c, state.rearSourceLabel, x + w - 5f, y + 9f, 9f, Color.rgb(170, 180, 191), align = Paint.Align.RIGHT)
        val ty = y + h + 18f
        val levelColor = when {
            certain -> if (blink(300)) Color.rgb(255, 90, 79) else Color.rgb(255, 208, 204)
            warn -> if (blink(400)) Color.rgb(255, 178, 30) else Color.rgb(168, 116, 42)
            else -> Color.rgb(139, 150, 163)
        }
        if (!state.rearGate.active) text(c, state.rearGate.reason, x, ty, 12f, Color.rgb(139, 150, 163), true)
        else text(c, judge.level.label, x, ty, 17f, levelColor, true)
        judge.last?.let { e ->
            text(c, "충돌까지 %.1f초 · 약 %.0f m · 접근 %.0f km/h".format(e.ttcS, e.distM, e.closingMps * 3.6f), x, ty + 22f, 12f, Color.rgb(201, 210, 219))
        }
        // 바퀴 각도 (충돌 확실 시 급속 조향 시뮬레이션)
        val gx = x + 34f
        val gy = ty + 76f
        val r = 26f
        val target = state.wheelSimDeg
        val prog = state.wheelSimAtMs?.let { ((now - it) / 250f).coerceIn(0f, 1f) } ?: 0f
        val deg = target * prog
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.rgb(89, 99, 111)
        c.drawCircle(gx, gy, r, p)
        c.save(); c.rotate(deg, gx, gy)
        p.style = Paint.Style.FILL; p.color = if (prog > 0f) Color.rgb(255, 211, 77) else Color.rgb(170, 180, 191)
        c.drawRect(gx - 6f, gy - r + 4f, gx + 6f, gy + r - 4f, p)
        c.restore()
        text(c, "바퀴 %.0f°".format(deg), x + 72f, gy - 9f, 15f, if (prog > 0f) Color.rgb(255, 211, 77) else Color.rgb(201, 210, 219), true)
        text(c, if (prog > 0f) "급속 조향 (시뮬레이션)" else "충돌 확실 시 급속 조향", x + 72f, gy + 11f, 11f, Color.rgb(154, 166, 178))
    }

    private fun drawCrash(c: Canvas, eng: TailGuardEngine) {
        val mid = BX + 168f
        if (!eng.doorsReleased) {
            text(c, "충돌 감지", mid, BY + 70f, 46f, if (blink(500)) Color.rgb(255, 107, 95) else Color.rgb(255, 208, 204), true, Paint.Align.CENTER)
            text(c, "차량이 멈추면 문을 엽니다", mid, BY + 130f, 19f, Color.rgb(240, 214, 212), align = Paint.Align.CENTER)
            val moving = eng.speedKmh > eng.cfg.safeMoveKmh
            text(c, if (moving) "차량 이동 중 · 문 잠금 유지" else "정지 확인 중", mid, BY + 170f, 15f, Color.rgb(170, 150, 150), align = Paint.Align.CENTER)
            return
        }
        val right = eng.cfg.shoulderSide == Side.RIGHT
        val seatX = if (right) BX + 34f else BX + 236f
        seatMap(c, seatX, BY, 1.05f, eng, doorsOpen = true)
        // 갓길 방향 화살표 + 가드레일
        c.save()
        if (!right) c.scale(-1f, 1f, mid, 0f)
        val ax = BX + 150f + (now % 500) / 500f * 22f
        val ay = BY + 64f
        path.reset()
        path.moveTo(ax, ay - 17f); path.lineTo(ax + 84f, ay - 17f); path.lineTo(ax + 84f, ay - 34f)
        path.lineTo(ax + 124f, ay); path.lineTo(ax + 84f, ay + 34f); path.lineTo(ax + 84f, ay + 17f)
        path.lineTo(ax, ay + 17f); path.close()
        p.style = Paint.Style.FILL; p.color = Color.rgb(255, 211, 77); c.drawPath(path, p)
        p.color = Color.rgb(207, 211, 214)
        for (i in 0 until 6) c.drawRect(BX + 318f, BY - 4f + i * 24f, BX + 326f, BY + 10f + i * 24f, p)
        c.restore()
        val tx = if (right) BX + 150f else BX
        text(c, "가드레일 밖으로 대피", tx, BY + 150f, 20f, Color.WHITE, true)
        text(c, "차 뒤에 서지 마세요", tx, BY + 180f, 19f, Color.rgb(255, 180, 173), true)
        state.incident?.let {
            text(c, "사고 기록 ${clock.format(Date(it.atMs))} · 탑승 ${it.occupants}명 · 벨트 미착용 ${it.unbelted}명", BX, BY + 236f, 14f, Color.rgb(154, 166, 178))
        }
    }

    // ---------- 오른쪽 상태 열 ----------

    private fun drawStatus(c: Canvas, eng: TailGuardEngine) {
        val occupied = Seat.entries.filter { eng.isOccupied(it) }
        val buckledN = occupied.count { eng.beltOf(it) == true }
        val anyUnbelted = occupied.any { eng.beltOf(it) == false }
        val askHazard = eng.phase != Phase.CRUISING || eng.jamAheadWarned
        val rows = listOf(
            Row("후미등", ctl(Device.TAIL_LAMP, state.rearLightsRequested, "ON", "자동")),
            Row("후방 안개등", ctl(Device.REAR_FOG, state.rearLightsRequested, "ON", "OFF")),
            if (askHazard) Row("비상등", "켜 주세요", Tone.ALERT) else Row("비상등", "—", Tone.IDLE),
            Row("안전벨트", if (occupied.isEmpty()) "—" else "$buckledN/${occupied.size}", if (anyUnbelted) Tone.ALERT else if (occupied.isNotEmpty() && buckledN == occupied.size) Tone.OK else Tone.IDLE),
            ctlRow("도어", Device.DOORS, state.doorsRequested, "잠금 해제"),
            ctlRow("창문", Device.WINDOWS, state.windowsRequested, "열림"),
            ctlRow("실내등", Device.CABIN_LIGHT, state.cabinRequested, "ON"),
        )
        val x = 380f
        rows.forEachIndexed { i, r ->
            val y = 100f + i * 41f
            rrect(c, x, y, 166f, 34f, 7f, Color.argb(12, 255, 255, 255))
            text(c, r.label, x + 11f, y + 17f, 14f, Color.rgb(170, 180, 191))
            val col = when (r.tone) {
                Tone.ALERT -> if (blink(600)) Color.rgb(255, 90, 79) else Color.rgb(122, 42, 38)
                Tone.OK -> Color.rgb(70, 211, 122)
                Tone.FAIL -> Color.rgb(127, 138, 150)
                Tone.PENDING -> Color.rgb(220, 226, 232)
                Tone.IDLE -> Color.rgb(127, 138, 150)
            }
            text(c, r.value, x + 156f, y + 17f, 14f, col, true, Paint.Align.RIGHT)
        }
    }

    private enum class Tone { OK, ALERT, FAIL, PENDING, IDLE }
    private data class Row(val label: String, val value: String, val tone: Tone = Tone.IDLE) {
        constructor(label: String, v: Pair<String, Tone>) : this(label, v.first, v.second)
    }

    private fun ctl(d: Device, requested: Boolean, on: String, off: String): Pair<String, Tone> =
        if (!requested) off to Tone.IDLE else when (state.results[d]) {
            true -> on to Tone.OK
            false -> "미지원" to Tone.FAIL
            null -> "요청 중" to Tone.PENDING
        }

    private fun ctlRow(label: String, d: Device, requested: Boolean, on: String) =
        Row(label, ctl(d, requested, on, "대기"))

    // ---------- 그림 부품 ----------

    /** 위에서 본 좌석 지도 (앞 2석 + 뒤 3석). 초록 = 착석·벨트, 빨강 점멸 = 착석·미착용, 회색 = 빈 좌석 */
    private fun seatMap(c: Canvas, x: Float, y: Float, sc: Float, eng: TailGuardEngine, doorsOpen: Boolean) {
        rrect(c, x, y, 76f * sc, 150f * sc, 24f * sc, Color.rgb(42, 48, 56), Color.rgb(89, 99, 111))
        val seats = listOf(
            Seat.DRIVER to floatArrayOf(14f, 34f, 22f), Seat.PASSENGER to floatArrayOf(40f, 34f, 22f),
            Seat.REAR_LEFT to floatArrayOf(9f, 92f, 18f), Seat.REAR_CENTER to floatArrayOf(29f, 92f, 18f),
            Seat.REAR_RIGHT to floatArrayOf(49f, 92f, 18f),
        )
        for ((seat, g) in seats) {
            val col = when {
                !eng.isOccupied(seat) -> Color.rgb(58, 66, 76)
                eng.beltOf(seat) == false -> if (blink(500)) Color.rgb(255, 59, 48) else Color.rgb(90, 28, 28)
                eng.beltOf(seat) == true -> Color.rgb(60, 196, 107)
                else -> Color.rgb(127, 154, 134)
            }
            rrect(c, x + g[0] * sc, y + g[1] * sc, g[2] * sc, 26f * sc, 5f * sc, col)
        }
        if (doorsOpen) {
            for (dy in floatArrayOf(40f, 98f)) {
                line(c, x + 76f * sc, y + (dy - 14f) * sc, x + 100f * sc, y + dy * sc, Color.rgb(255, 211, 77), 3f)
                line(c, x, y + (dy - 14f) * sc, x - 24f * sc, y + dy * sc, Color.rgb(255, 211, 77), 3f)
            }
        }
    }

    private fun topCar(c: Canvas, cx: Float, cy: Float, sc: Float, body: Int) {
        rrect(c, cx - 22f * sc, cy - 44f * sc, 44f * sc, 88f * sc, 14f * sc, body)
        rrect(c, cx - 17f * sc, cy - 26f * sc, 34f * sc, 16f * sc, 4f * sc, Color.rgb(70, 80, 92))
        rrect(c, cx - 17f * sc, cy + 18f * sc, 34f * sc, 12f * sc, 4f * sc, Color.rgb(70, 80, 92))
        p.style = Paint.Style.FILL; p.color = Color.rgb(255, 42, 26)
        c.drawRect(cx - 20f * sc, cy + 38f * sc, cx - 8f * sc, cy + 43f * sc, p)
        c.drawRect(cx + 8f * sc, cy + 38f * sc, cx + 20f * sc, cy + 43f * sc, p)
    }

    /** 경로 막대: 왼쪽 끝 = 내 차, 빨강 = 정체 구간 (3 km 범위) */
    private fun routeBar(c: Canvas, x: Float, y: Float, w: Float, jamM: Int?) {
        rrect(c, x, y - 6f, w, 12f, 6f, Color.rgb(43, 49, 57))
        if (jamM != null) {
            val jx = x + 22f + (jamM / 1000f).coerceIn(0f, 3f) * (w - 40f) / 3f
            rrect(c, jx, y - 6f, maxOf(18f, x + w - jx), 12f, 6f, Color.rgb(224, 69, 58))
            rrect(c, maxOf(x, jx - 34f), y - 6f, 34f, 12f, 3f, Color.rgb(255, 197, 77))
        } else {
            rrect(c, x + 22f, y - 6f, w - 22f, 12f, 6f, Color.rgb(47, 143, 85))
        }
        path.reset(); path.moveTo(x + 4f, y - 11f); path.lineTo(x + 20f, y); path.lineTo(x + 4f, y + 11f); path.close()
        p.style = Paint.Style.FILL; p.color = Color.rgb(127, 209, 255); c.drawPath(path, p)
    }

    /** 설정: 후방 감지 켜기/끄기, 시내에서 동작, 정체 구간에서만. 줄을 누르면 바뀌고 바로 저장된다. */
    private fun drawSettings(c: Canvas) {
        rrect(c, 8f, 92f, W0 - 16f, H0 - 100f, 10f, Color.argb(245, 12, 15, 20), Color.rgb(60, 70, 80))
        text(c, "후방 대형차 접근 감지 설정", 40f, 112f, 17f, Color.rgb(232, 237, 242), true)
        val s = state.settings
        val items = listOf(
            Triple("후방 대형차 접근 감지", "후방 카메라로 급속 접근하는 버스·트럭 감지", s.enabled),
            Triple("시내에서도 동작", "지도 위치로 시내 도로 판단 (기본 꺼짐)", s.inCity),
            Triple("정체 구간에서만 동작", "내비 정체 1.5km 안 · 정체 꼬리 경고 · 정차 대비", s.jamOnly),
        )
        items.forEachIndexed { i, (title, sub, on) ->
            val r = settingRows[i]
            val dim = i > 0 && !s.enabled
            rrect(c, r.left, r.top, r.width(), r.height(), 9f, Color.argb(16, 255, 255, 255))
            val tc = if (dim) Color.rgb(100, 110, 120) else Color.rgb(226, 232, 238)
            text(c, title, r.left + 14f, r.top + 18f, 16f, tc, true)
            text(c, sub, r.left + 14f, r.top + 40f, 12f, if (dim) Color.rgb(80, 88, 96) else Color.rgb(139, 150, 163))
            // 스위치
            val sx = r.right - 64f
            val sy = r.centerY()
            rrect(c, sx, sy - 13f, 50f, 26f, 13f, if (on && !dim) Color.rgb(47, 143, 85) else Color.rgb(58, 66, 76))
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawCircle(if (on) sx + 37f else sx + 13f, sy, 10f, p)
        }
        text(c, "현재 도로: ${state.road.label} · ${state.rearGate.reason}", 40f, 352f, 13f, Color.rgb(170, 180, 191))
        text(c, "저속 접근(30 km/h 미만)·차간거리를 좁혀 붙는 차에는 반응하지 않습니다", 40f, 372f, 12f, Color.rgb(127, 138, 150))
    }

    private fun drawLog(c: Canvas) {
        rrect(c, 8f, 92f, W0 - 16f, H0 - 100f, 10f, Color.argb(235, 8, 10, 13), Color.rgb(60, 70, 80))
        val lines = state.logs.toList().takeLast(19)
        p.typeface = mono
        lines.forEachIndexed { i, s ->
            p.style = Paint.Style.FILL; p.textSize = 11f; p.color = Color.rgb(170, 200, 170); p.textAlign = Paint.Align.LEFT
            c.drawText(s.take(90), 18f, 112f + i * 15f, p)
        }
    }

    // ---------- 도우미 ----------

    private fun blink(periodMs: Long) = (now / periodMs) % 2 == 0L

    private fun km(m: Int): String {
        val tenths = (m.coerceAtLeast(100) + 50) / 100
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun phaseColor(ph: Phase) = when (ph) {
        Phase.CRUISING -> Color.rgb(30, 107, 58)
        Phase.TAIL_ENTRY -> Color.rgb(209, 122, 31)
        Phase.STOPPED_IN_QUEUE -> Color.rgb(194, 74, 26)
        Phase.POST_CRASH -> Color.rgb(179, 23, 27)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, isBold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        p.style = Paint.Style.FILL; p.textSize = size; p.color = color; p.textAlign = align
        p.typeface = if (isBold) bold else Typeface.DEFAULT
        c.drawText(s, x, y - (p.descent() + p.ascent()) / 2, p)
    }

    private fun width(s: String, size: Float, isBold: Boolean): Float {
        p.textSize = size; p.typeface = if (isBold) bold else Typeface.DEFAULT
        return p.measureText(s)
    }

    private fun rrect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, stroke: Int? = null) {
        rect.set(x, y, x + w, y + h)
        p.style = Paint.Style.FILL; p.color = fill; c.drawRoundRect(rect, r, r, p)
        if (stroke != null) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = stroke; c.drawRoundRect(rect, r, r, p)
        }
    }

    private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = color; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(x0, y0, x1, y1, p)
    }

    companion object {
        private const val W0 = 560f
        private const val H0 = 400f
        private const val BX = 16f
        private const val BY = 104f
        private const val FRAME_MS = 100L
        private val BG = Color.rgb(14, 18, 23)
    }
}
