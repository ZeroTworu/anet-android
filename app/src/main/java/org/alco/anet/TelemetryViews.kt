package org.alco.anet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Линейный пульс пинга ("Вы ───────●── 📶 58 мс Сеть/Туннель").
 * Отображает цветную линию (Красный / Жёлтый / Зелёный), бегущий импульс
 * и индикатор уровня сигнала (4 столбика) перед текстом задержки в мс.
 */
class PulseLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val PULSE_RED = 0
        const val PULSE_YELLOW = 1
        const val PULSE_GREEN = 2
        const val PULSE_GRAY = 3

        val COLOR_RED = Color.parseColor("#FF5252")
        val COLOR_YELLOW = Color.parseColor("#FFC107")
        val COLOR_GREEN = Color.parseColor("#00E676")
        val COLOR_GRAY = Color.parseColor("#9E9E9E")
        val COLOR_TRACK_DIM = Color.parseColor("#2C2C30")
    }

    private var pulseColorCode: Int = PULSE_YELLOW
    private var rttMs: Long = -1L

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        color = COLOR_TRACK_DIM
    }

    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private var animProgress = 0.65f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2100L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            animProgress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    fun updateState(colorCode: Int, rttMilliseconds: Long) {
        val changed = (this.pulseColorCode != colorCode || this.rttMs != rttMilliseconds)
        this.pulseColorCode = colorCode
        this.rttMs = rttMilliseconds
        if (changed) {
            invalidate()
        }
    }

    fun getActiveColor(): Int = when (pulseColorCode) {
        PULSE_RED -> COLOR_RED
        PULSE_YELLOW -> COLOR_YELLOW
        PULSE_GRAY -> COLOR_GRAY
        else -> COLOR_GREEN
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulseAnimator.isStarted) {
            pulseAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cy = h / 2f
        val activeColor = getActiveColor()

        // Область справа под 4 столбика сигнала (ширина ~18dp)
        val barsAreaWidth = 18f * density
        val gapBeforeBars = 8f * density
        val lineStartX = 4f * density
        val lineEndX = max(lineStartX + 12f * density, w - barsAreaWidth - gapBeforeBars)
        val lineLen = lineEndX - lineStartX

        // 1. Базовая подложка линии
        canvas.drawLine(lineStartX, cy, lineEndX, cy, trackPaint)

        // 2. Позиция бегущего импульса
        val dotX = if (pulseColorCode == PULSE_RED) {
            lineStartX + lineLen * 0.25f
        } else {
            lineStartX + lineLen * (0.12f + 0.78f * animProgress)
        }

        // 3. Градиентный след вдоль линии до точки импульса
        val transparentActive = Color.argb(
            40,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor)
        )
        val midActive = Color.argb(
            180,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor)
        )
        activeLinePaint.shader = LinearGradient(
            lineStartX,
            cy,
            lineEndX,
            cy,
            intArrayOf(transparentActive, midActive, activeColor, transparentActive),
            floatArrayOf(
                0f,
                max(0.05f, (dotX - lineStartX) / lineLen - 0.15f),
                min(0.95f, (dotX - lineStartX) / lineLen),
                1f
            ),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(lineStartX, cy, lineEndX, cy, activeLinePaint)

        // 4. Свечение вокруг точки (ореол)
        val glowRadius = 7.5f * density
        glowPaint.shader = RadialGradient(
            dotX,
            cy,
            glowRadius,
            intArrayOf(
                Color.argb(150, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(dotX, cy, glowRadius, glowPaint)

        // 5. Ядро точки импульса
        dotPaint.color = activeColor
        canvas.drawCircle(dotX, cy, 3.2f * density, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(dotX, cy, 1.3f * density, dotPaint)

        // 6. Рисуем 4 столбика уровня сигнала справа от линии
        val litBars = when (pulseColorCode) {
            PULSE_RED -> 0
            PULSE_YELLOW -> 2
            else -> when {
                rttMs in 1..85 -> 4
                rttMs in 86..170 -> 3
                rttMs > 170 -> 2
                else -> 0
            }
        }

        val barWidth = 2.6f * density
        val barSpacing = 1.8f * density
        val maxBarHeight = min(h * 0.72f, 12f * density)
        val barsStartX = w - barsAreaWidth + 1f * density
        val barsBottomY = cy + maxBarHeight / 2f

        for (i in 0 until 4) {
            val fraction = (i + 1) / 4f
            val barH = maxBarHeight * (0.32f + 0.68f * fraction)
            val left = barsStartX + i * (barWidth + barSpacing)
            val top = barsBottomY - barH
            barRect.set(left, top, left + barWidth, barsBottomY)
            barPaint.color = if (i < litBars) activeColor else COLOR_TRACK_DIM
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }
}

/**
 * Сегментированная шкала ответивших узлов (как в детекторе связности:
 * сначала узлы РФ/БС, затем глобальные внешние узлы).
 */
class ConnectivitySegmentsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var domesticOk: Int = 0
    private var domesticTotal: Int = 3
    private var globalOk: Int = 0
    private var globalTotal: Int = 3
    private var pulseColorCode: Int = PulseLineView.PULSE_YELLOW

    private val density = resources.displayMetrics.density
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val segRect = RectF()

    fun setProbes(
        domOk: Int,
        domTotal: Int,
        globOk: Int,
        globTotal: Int,
        colorCode: Int
    ) {
        this.domesticOk = domOk.coerceAtLeast(0)
        this.domesticTotal = domTotal.coerceAtLeast(1)
        this.globalOk = globOk.coerceAtLeast(0)
        this.globalTotal = globTotal.coerceAtLeast(1)
        this.pulseColorCode = colorCode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalSegments = (domesticTotal + globalTotal).coerceAtLeast(1)
        val smallGap = 4f * density
        val groupGap = 9f * density
        val totalGapsWidth = (totalSegments - 2).coerceAtLeast(0) * smallGap + groupGap
        val segWidth = ((w - totalGapsWidth) / totalSegments).coerceAtLeast(4f * density)
        val segHeight = min(h, 4.5f * density)
        val top = (h - segHeight) / 2f
        val radius = segHeight / 2f

        val activeColor = when (pulseColorCode) {
            PulseLineView.PULSE_RED -> PulseLineView.COLOR_RED
            PulseLineView.PULSE_YELLOW -> PulseLineView.COLOR_YELLOW
            else -> PulseLineView.COLOR_GREEN
        }
        val dimColor = Color.parseColor("#2A2A2E")

        var x = 0f
        // Отрисовка сегментов РФ (БС)
        for (i in 0 until domesticTotal) {
            segRect.set(x, top, x + segWidth, top + segHeight)
            segmentPaint.color = if (i < domesticOk) activeColor else dimColor
            canvas.drawRoundRect(segRect, radius, radius, segmentPaint)
            x += segWidth + if (i == domesticTotal - 1) groupGap else smallGap
        }

        // Отрисовка сегментов внешнего интернета (Мир)
        for (i in 0 until globalTotal) {
            segRect.set(x, top, min(w, x + segWidth), top + segHeight)
            segmentPaint.color = if (i < globalOk) activeColor else dimColor
            canvas.drawRoundRect(segRect, radius, radius, segmentPaint)
            x += segWidth + smallGap
        }
    }
}

/**
 * Плавный 60-точечный график скорости RX / TX (карточка СКОРОСТЬ / ТРАФИК).
 */
class TelemetrySparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val HISTORY_SIZE = 60
    }

    private var rxCurve = FloatArray(HISTORY_SIZE) { 0f }
    private var txCurve = FloatArray(HISTORY_SIZE) { 0f }

    // Локальный кольцевой буфер для fallback-обновлений из строковой статистики
    private val rxRawHistory = DoubleArray(HISTORY_SIZE) { 0.0 }
    private val txRawHistory = DoubleArray(HISTORY_SIZE) { 0.0 }

    private val density = resources.displayMetrics.density
    private val rxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#E8F5E9")
    }

    private val txStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#88EEBC7A")
    }

    private val rxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#24FFFFFF")
    }

    private val rxPath = Path()
    private val rxFillPath = Path()
    private val txPath = Path()

    fun setCurves(newRx: FloatArray, newTx: FloatArray) {
        if (newRx.isNotEmpty()) {
            rxCurve = if (newRx.size == HISTORY_SIZE) newRx.clone() else resample(newRx, HISTORY_SIZE)
        }
        if (newTx.isNotEmpty()) {
            txCurve = if (newTx.size == HISTORY_SIZE) newTx.clone() else resample(newTx, HISTORY_SIZE)
        }
        invalidate()
    }

    fun pushFallbackSpeedSample(rxBps: Double, txBps: Double) {
        System.arraycopy(rxRawHistory, 1, rxRawHistory, 0, HISTORY_SIZE - 1)
        System.arraycopy(txRawHistory, 1, txRawHistory, 0, HISTORY_SIZE - 1)
        rxRawHistory[HISTORY_SIZE - 1] = rxBps.coerceAtLeast(0.0)
        txRawHistory[HISTORY_SIZE - 1] = txBps.coerceAtLeast(0.0)

        var peak = 1024.0
        for (i in 0 until HISTORY_SIZE) {
            if (rxRawHistory[i] > peak) peak = rxRawHistory[i]
            if (txRawHistory[i] > peak) peak = txRawHistory[i]
        }
        for (i in 0 until HISTORY_SIZE) {
            rxCurve[i] = (rxRawHistory[i] / peak).toFloat().coerceIn(0f, 1f)
            txCurve[i] = (txRawHistory[i] / peak).toFloat().coerceIn(0f, 1f)
        }
        invalidate()
    }

    fun clearGraph() {
        for (i in 0 until HISTORY_SIZE) {
            rxCurve[i] = 0f
            txCurve[i] = 0f
            rxRawHistory[i] = 0.0
            txRawHistory[i] = 0.0
        }
        invalidate()
    }

    private fun resample(src: FloatArray, targetLen: Int): FloatArray {
        val out = FloatArray(targetLen)
        if (src.isEmpty()) return out
        for (i in 0 until targetLen) {
            val idx = (i * (src.size - 1) / max(1, targetLen - 1)).coerceIn(0, src.lastIndex)
            out[i] = src[idx].coerceIn(0f, 1f)
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val topPad = 3f * density
        val bottomPad = 2f * density
        val usableH = (h - topPad - bottomPad).coerceAtLeast(4f)
        val baseY = h - bottomPad

        // Базовая тонкая линия внизу графика
        canvas.drawLine(0f, baseY, w, baseY, baselinePaint)

        buildSmoothPath(txCurve, w, topPad, usableH, baseY, txPath, null)
        canvas.drawPath(txPath, txStrokePaint)

        buildSmoothPath(rxCurve, w, topPad, usableH, baseY, rxPath, rxFillPath)
        rxFillPaint.shader = LinearGradient(
            0f,
            topPad,
            0f,
            baseY,
            intArrayOf(
                Color.argb(95, 0, 230, 118),
                Color.argb(10, 0, 230, 118)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(rxFillPath, rxFillPaint)
        canvas.drawPath(rxPath, rxStrokePaint)
    }

    private fun buildSmoothPath(
        points: FloatArray,
        width: Float,
        topPad: Float,
        usableH: Float,
        baseY: Float,
        outStroke: Path,
        outFill: Path?
    ) {
        outStroke.reset()
        outFill?.reset()
        val n = points.size
        if (n < 2) return

        val stepX = width / (n - 1).toFloat()
        var prevX = 0f
        var prevY = baseY - (points[0].coerceIn(0f, 1f) * usableH)
        outStroke.moveTo(prevX, prevY)
        outFill?.moveTo(0f, baseY)
        outFill?.lineTo(prevX, prevY)

        for (i in 1 until n) {
            val curX = i * stepX
            val curY = baseY - (points[i].coerceIn(0f, 1f) * usableH)
            val midX = (prevX + curX) / 2f
            outStroke.cubicTo(midX, prevY, midX, curY, curX, curY)
            outFill?.cubicTo(midX, prevY, midX, curY, curX, curY)
            prevX = curX
            prevY = curY
        }

        outFill?.lineTo(width, baseY)
        outFill?.close()
    }
}
