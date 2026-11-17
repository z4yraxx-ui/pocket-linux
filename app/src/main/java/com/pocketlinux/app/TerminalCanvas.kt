package com.pocketlinux.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

private val DEFAULT_BG = 0xFF0B0F1A.toInt()
private val DEFAULT_FG = 0xFFD7E3F4.toInt()
private val CURSOR_COLOR = 0xAA77E0B5.toInt()
private const val STYLE_MASK = Terminal.BOLD or Terminal.DIM or Terminal.ITALIC or Terminal.UNDERLINE

/**
 * Desenha a tela do [Terminal]. Mede a célula da fonte, avisa o tamanho em linhas e colunas
 * ([onResize]) e rola o histórico ao arrastar o dedo na vertical ([onScrollBy]).
 */
@Composable
internal fun TerminalCanvas(
    terminal: Terminal,
    scrollBack: Int,
    onScrollBy: (Int) -> Unit,
    onResize: (rows: Int, cols: Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { 13.sp.toPx() }
        }
    }
    val bgPaint = remember { Paint() }
    val cellW = remember(textPaint) { textPaint.measureText("M") }
    val cellH = remember(textPaint) {
        val fm = textPaint.fontMetrics
        ceil((fm.descent - fm.ascent).toDouble()).toFloat()
    }
    val ascent = remember(textPaint) { -textPaint.fontMetrics.ascent }

    val revision by terminal.revision.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragAcc by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(size, cellW, cellH) {
        if (size.width > 0 && size.height > 0) {
            onResize(
                maxOf(2, (size.height / cellH).toInt()),
                maxOf(10, (size.width / cellW).toInt())
            )
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(cellH) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    dragAcc += dy
                    val lines = (dragAcc / cellH).toInt()
                    if (lines != 0) {
                        dragAcc -= lines * cellH
                        onScrollBy(lines)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Ler "revision" aqui faz o Compose redesenhar a cada mudança na tela.
            if (revision < 0) return@Canvas
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.drawColor(DEFAULT_BG)
                terminal.read { t ->
                    drawTerminal(nc, t, scrollBack, cellW, cellH, ascent, textPaint, bgPaint)
                }
            }
        }
    }
}

private fun effFg(row: Terminal.Row, x: Int): Int {
    val reversed = (row.attr[x] and Terminal.REVERSE) != 0
    val c = if (reversed) row.bg[x] else row.fg[x]
    return if (c != 0) c else if (reversed) DEFAULT_BG else DEFAULT_FG
}

private fun effBg(row: Terminal.Row, x: Int): Int {
    val reversed = (row.attr[x] and Terminal.REVERSE) != 0
    val c = if (reversed) row.fg[x] else row.bg[x]
    return if (c != 0) c else if (reversed) DEFAULT_FG else DEFAULT_BG
}

private fun drawTerminal(
    nc: android.graphics.Canvas,
    t: Terminal,
    scrollBack: Int,
    cellW: Float,
    cellH: Float,
    ascent: Float,
    textPaint: Paint,
    bgPaint: Paint
) {
    val total = t.virtualRowCount()
    val back = scrollBack.coerceIn(0, maxOf(0, total - t.rows))
    val topIndex = total - t.rows - back
    val sb = StringBuilder()

    for (vr in 0 until t.rows) {
        val idx = topIndex + vr
        if (idx < 0 || idx >= total) continue
        val row = t.virtualRow(idx)
        val y = vr * cellH
        val w = minOf(row.width, t.cols)

        // Fundos coloridos, em blocos de células iguais.
        var x = 0
        while (x < w) {
            val b = effBg(row, x)
            var e = x + 1
            while (e < w && effBg(row, e) == b) e++
            if (b != DEFAULT_BG) {
                bgPaint.color = b
                nc.drawRect(x * cellW, y, e * cellW, y + cellH, bgPaint)
            }
            x = e
        }

        // Texto, em blocos com o mesmo estilo e a mesma cor.
        x = 0
        while (x < w) {
            val a = row.attr[x] and STYLE_MASK
            val f = effFg(row, x)
            var e = x + 1
            while (e < w && (row.attr[e] and STYLE_MASK) == a && effFg(row, e) == f) e++
            sb.setLength(0)
            var blank = true
            for (k in x until e) {
                val cp = row.chars[k]
                if (cp != 32) blank = false
                sb.appendCodePoint(cp)
            }
            val underline = (a and Terminal.UNDERLINE) != 0
            if (!blank || underline) {
                textPaint.color = if ((a and Terminal.DIM) != 0) (f and 0x00FFFFFF) or 0x99000000.toInt() else f
                textPaint.isFakeBoldText = (a and Terminal.BOLD) != 0
                textPaint.textSkewX = if ((a and Terminal.ITALIC) != 0) -0.25f else 0f
                textPaint.isUnderlineText = underline
                nc.drawText(sb, 0, sb.length, x * cellW, y + ascent, textPaint)
            }
            x = e
        }
    }

    if (back == 0 && t.cursorVisible) {
        bgPaint.color = CURSOR_COLOR
        nc.drawRect(
            t.cursorCol * cellW,
            t.cursorRow * cellH,
            (t.cursorCol + 1) * cellW,
            (t.cursorRow + 1) * cellH,
            bgPaint
        )
    }

    textPaint.isFakeBoldText = false
    textPaint.textSkewX = 0f
    textPaint.isUnderlineText = false
}
