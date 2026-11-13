package ai.tailguard.vehicle

import ai.tailguard.core.RearDetection
import ai.tailguard.core.VehicleKind
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 후방 카메라 한 프레임에서 뒤차(승용차·버스·트럭)를 찾는다. 기기 안에서 실행 (네트워크 불필요).
 * 모델: EfficientDet-Lite0 (COCO 2017 학습, 80종 중 car·bus·truck 만 사용), assets/efficientdet_lite0.tflite
 * 실행: TensorFlow Lite 인터프리터 (ARM 차량 기기와 x86_64 에뮬레이터 모두 지원)
 * 가장 큰 박스 = 가장 가까운 뒤차로 본다.
 */
class RearVehicleDetector(context: Context, private val log: (String) -> Unit) {

    data class Result(val detection: RearDetection?, val box: RectF?, val label: String?)

    private val interpreter: Interpreter? = runCatching {
        val fd = context.assets.openFd(MODEL)
        val model = FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        Interpreter(model, Interpreter.Options().setNumThreads(2))
    }.onFailure { log("후방 차량 인식 모델 로드 실패: ${it.message}") }.getOrNull()

    private val inH: Int
    private val inW: Int
    private val inType: DataType
    private val input: ByteBuffer

    init {
        val t = interpreter?.getInputTensor(0)
        val shape = t?.shape() ?: intArrayOf(1, 320, 320, 3)
        inH = shape[1]; inW = shape[2]
        inType = t?.dataType() ?: DataType.UINT8
        val bytes = if (inType == DataType.FLOAT32) 4 else 1
        input = ByteBuffer.allocateDirect(inH * inW * 3 * bytes).order(ByteOrder.nativeOrder())
        if (interpreter != null) {
            val outs = (0 until interpreter.outputTensorCount).joinToString { i ->
                val o = interpreter.getOutputTensor(i); "${o.name()}${o.shape().contentToString()}"
            }
            log("후방 차량 인식 모델: 입력 ${inW}x$inH $inType · 출력 $outs")
        }
    }

    val ready: Boolean get() = interpreter != null

    fun detect(frame: Bitmap): Result {
        val itp = interpreter ?: return EMPTY
        return runCatching { run(itp, frame) }.onFailure { log("후방 차량 인식 실패: ${it.message}") }.getOrDefault(EMPTY)
    }

    private fun run(itp: Interpreter, frame: Bitmap): Result {
        val scaled = Bitmap.createScaledBitmap(frame, inW, inH, true)
        val px = IntArray(inW * inH)
        scaled.getPixels(px, 0, inW, 0, 0, inW, inH)
        input.rewind()
        for (c in px) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (inType == DataType.FLOAT32) {
                input.putFloat((r - 127.5f) / 127.5f); input.putFloat((g - 127.5f) / 127.5f); input.putFloat((b - 127.5f) / 127.5f)
            } else {
                input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte())
            }
        }
        input.rewind()

        // 출력: 박스 [1,N,4](ymin,xmin,ymax,xmax, 0~1) · 종류 [1,N] · 점수 [1,N] · 개수 [1] — 순서가 모델마다 달라 모양과 값으로 가린다
        val outputs = HashMap<Int, Any>()
        val arrays = ArrayList<Pair<Int, IntArray>>()
        for (i in 0 until itp.outputTensorCount) {
            val shape = itp.getOutputTensor(i).shape()
            arrays += i to shape
            outputs[i] = when (shape.size) {
                3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                2 -> Array(shape[0]) { FloatArray(shape[1]) }
                else -> FloatArray(shape.fold(1) { a, b -> a * b })
            }
        }
        itp.runForMultipleInputsOutputs(arrayOf(input), outputs)

        @Suppress("UNCHECKED_CAST")
        val boxes = (outputs[arrays.first { it.second.size == 3 }.first] as Array<Array<FloatArray>>)[0]
        @Suppress("UNCHECKED_CAST")
        val vecs = arrays.filter { it.second.size == 2 }.map { (outputs[it.first] as Array<FloatArray>)[0] }
        if (vecs.size < 2) return EMPTY
        // 종류 = 정수 값, 점수 = 0~1 소수
        val classes = vecs.maxBy { v -> v.count { it > 1.5f && it == Math.round(it).toFloat() } }
        val scores = vecs.first { it !== classes }

        // 바로 뒤 차로의 차를 고른다: 박스 중심이 화면 가운데 60% 안인 것 중 가장 큰 것 (없으면 전체 중 가장 큰 것)
        var best = -1
        var bestW = 0f
        var bestCentered = false
        for (k in scores.indices) {
            if (scores[k] < SCORE_MIN) continue
            if (classes[k].toInt() !in KINDS) continue
            val w = boxes[k][3] - boxes[k][1]
            val cx = (boxes[k][1] + boxes[k][3]) / 2f
            val centered = cx in 0.2f..0.8f
            if ((centered && !bestCentered) || (centered == bestCentered && w > bestW)) { bestW = w; best = k; bestCentered = centered }
        }
        if (best < 0) return EMPTY
        val kind = KINDS.getValue(classes[best].toInt())
        val b = boxes[best]
        val box = RectF(b[1].coerceIn(0f, 1f), b[0].coerceIn(0f, 1f), b[3].coerceIn(0f, 1f), b[2].coerceIn(0f, 1f))
        val score = scores[best]
        return Result(RearDetection(kind, box.width(), score), box, "${kind.label} ${(score * 100).toInt()}%")
    }

    fun close() { interpreter?.close() }

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"
        private const val SCORE_MIN = 0.3f
        /** COCO 라벨 번호 (0부터): 2 car, 5 bus, 7 truck */
        private val KINDS = mapOf(2 to VehicleKind.CAR, 5 to VehicleKind.BUS, 7 to VehicleKind.TRUCK)
        private val EMPTY = Result(null, null, null)
    }
}
