package com.pocketlinux.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Emulador de terminal (um subconjunto de xterm/VT100 suficiente para bash, nano, top, htop, vim, less).
 * Recebe bytes do processo em [feed] e mantém a grade de caracteres com cores, o cursor,
 * o histórico de rolagem e a tela alternativa. Código próprio.
 *
 * Seguro entre threads: [feed] roda na thread que lê o PTY e a interface lê com [read].
 */
class Terminal(initialRows: Int = 24, initialCols: Int = 80, private val maxScrollback: Int = 3000) {

    /** Uma linha da tela: caractere, cor da frente, cor do fundo e atributos de cada célula. */
    class Row(val width: Int) {
        val chars = IntArray(width) { 32 }
        val fg = IntArray(width)
        val bg = IntArray(width)
        val attr = IntArray(width)

        fun clear(from: Int, to: Int, bgColor: Int) {
            val end = minOf(to, width)
            for (i in maxOf(from, 0) until end) {
                chars[i] = 32
                fg[i] = 0
                bg[i] = bgColor
                attr[i] = 0
            }
        }

        fun copyCell(dst: Int, src: Int) {
            chars[dst] = chars[src]
            fg[dst] = fg[src]
            bg[dst] = bg[src]
            attr[dst] = attr[src]
        }

        fun resized(newWidth: Int): Row {
            val r = Row(newWidth)
            val n = minOf(width, newWidth)
            for (i in 0 until n) r.copyFrom(i, this, i)
            return r
        }

        private fun copyFrom(dst: Int, other: Row, src: Int) {
            chars[dst] = other.chars[src]
            fg[dst] = other.fg[src]
            bg[dst] = other.bg[src]
            attr[dst] = other.attr[src]
        }

        fun text(): String {
            var last = width - 1
            while (last >= 0 && chars[last] == 32) last--
            val sb = StringBuilder()
            for (i in 0..last) sb.appendCodePoint(chars[i])
            return sb.toString()
        }
    }

    private val lock = Any()

    /** Sobe a cada mudança; a interface observa para redesenhar. */
    val revision = MutableStateFlow(0)

    /** Respostas do terminal ao programa (posição do cursor, identificação). */
    @Volatile
    var onReply: ((ByteArray) -> Unit)? = null

    var rows = initialRows
        private set
    var cols = initialCols
        private set

    private var grid: Array<Row> = Array(initialRows) { Row(initialCols) }
    private val scrollback = ArrayDeque<Row>()
    private var savedMain: Array<Row>? = null

    var altScreen = false
        private set
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    /** Teclas de seta mudam de "ESC [ A" para "ESC O A" quando o programa liga este modo. */
    var appCursorKeys = false
        private set
    var bracketedPaste = false
        private set

    private var wrapPending = false
    private var autoWrap = true
    private var insertMode = false
    private var scrollTop = 0
    private var scrollBottom = initialRows - 1
    private var curFg = 0
    private var curBg = 0
    private var curAttr = 0

    private var savedRow = 0
    private var savedCol = 0
    private var savedFg = 0
    private var savedBg = 0
    private var savedAttr = 0

    private var tabStops = BooleanArray(initialCols)

    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false

    // ----- estado do interpretador de sequências -----
    private var state = GROUND
    private val params = IntArray(16)
    private var paramCount = 0
    private var curParam = -1
    private var privMarker = 0
    private var intermediate = 0
    private var escIntermediate = 0
    private var utf8Need = 0
    private var utf8Cp = 0

    init {
        resetTabs()
    }

    // ================= API pública =================

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        synchronized(lock) {
            for (i in off until off + len) processByte(data[i].toInt() and 0xFF)
        }
        revision.update { it + 1 }
    }

    /** Lê a tela com exclusão mútua (para desenhar). */
    fun <T> read(block: (Terminal) -> T): T = synchronized(lock) { block(this) }

    fun reset() {
        synchronized(lock) { resetLocked() }
        revision.update { it + 1 }
    }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows < 2 || newCols < 2) return
        synchronized(lock) {
            if (newRows == rows && newCols == cols) return
            var drop = 0
            if (newRows < rows) {
                // O que não cabe sobe para o histórico, mantendo o cursor visível.
                drop = maxOf(0, cursorRow - (newRows - 1))
                if (!altScreen) {
                    for (i in 0 until drop) pushScrollback(grid[i])
                }
            }
            val old = grid
            grid = Array(newRows) { r ->
                val src = r + drop
                if (src < old.size) old[src].resized(newCols) else Row(newCols)
            }
            val sm = savedMain
            if (sm != null) {
                savedMain = Array(newRows) { r -> if (r < sm.size) sm[r].resized(newCols) else Row(newCols) }
            }
            rows = newRows
            cols = newCols
            cursorRow = (cursorRow - drop).coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            scrollTop = 0
            scrollBottom = rows - 1
            wrapPending = false
            resetTabs()
        }
        revision.update { it + 1 }
    }

    // ----- leitura para desenhar (chamar dentro de [read]) -----

    /** Linhas de histórico (zero na tela alternativa, que não tem histórico). */
    fun scrollbackLineCount(): Int = if (altScreen) 0 else scrollback.size

    /** Histórico + tela. */
    fun virtualRowCount(): Int = scrollbackLineCount() + rows

    fun virtualRow(index: Int): Row {
        val sb = scrollbackLineCount()
        return if (index < sb) scrollback[index] else grid[index - sb]
    }

    /** Texto das últimas [maxLines] linhas (histórico + tela), sem espaços no fim. */
    fun transcript(maxLines: Int = Int.MAX_VALUE): String = synchronized(lock) {
        val total = virtualRowCount()
        val start = maxOf(0, total - maxLines)
        val lines = ArrayList<String>()
        for (i in start until total) lines.add(virtualRow(i).text())
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        lines.joinToString("\n")
    }

    // ================= interpretador =================

    private fun resetLocked() {
        grid = Array(rows) { Row(cols) }
        scrollback.clear()
        savedMain = null
        altScreen = false
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        appCursorKeys = false
        bracketedPaste = false
        wrapPending = false
        autoWrap = true
        insertMode = false
        scrollTop = 0
        scrollBottom = rows - 1
        curFg = 0
        curBg = 0
        curAttr = 0
        g0Graphics = false
        g1Graphics = false
        useG1 = false
        state = GROUND
        utf8Need = 0
        resetTabs()
    }

    private fun resetTabs() {
        tabStops = BooleanArray(cols) { it % 8 == 0 }
    }

    private fun reply(text: String) {
        onReply?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    private fun processByte(b: Int) {
        if (state == GROUND) {
            if (utf8Need > 0) {
                if ((b and 0xC0) == 0x80) {
                    utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                    utf8Need--
                    if (utf8Need == 0) putChar(utf8Cp)
                    return
                }
                utf8Need = 0
                putChar(0xFFFD)
            }
            when {
                b < 0x20 -> control(b)
                b == 0x7F -> Unit
                b < 0x80 -> putChar(b)
                b in 0xC2..0xDF -> {
                    utf8Cp = b and 0x1F
                    utf8Need = 1
                }
                b in 0xE0..0xEF -> {
                    utf8Cp = b and 0x0F
                    utf8Need = 2
                }
                b in 0xF0..0xF4 -> {
                    utf8Cp = b and 0x07
                    utf8Need = 3
                }
                else -> putChar(0xFFFD)
            }
            return
        }
        when (state) {
            ESC -> escByte(b)
            ESC_INT -> {
                state = GROUND
                designateCharset(b)
            }
            CSI -> csiByte(b)
            STR -> strByte(b)
            STR_ESC -> {
                if (b == '\\'.code) {
                    state = GROUND
                } else {
                    state = ESC
                    escByte(b)
                }
            }
            else -> state = GROUND
        }
    }

    private fun control(b: Int) {
        when (b) {
            0x08 -> { // BS
                if (cursorCol > 0) cursorCol--
                wrapPending = false
            }
            0x09 -> { // TAB
                if (cursorCol < cols - 1) {
                    cursorCol++
                    while (cursorCol < cols - 1 && !tabStops[cursorCol]) cursorCol++
                }
                wrapPending = false
            }
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> {
                cursorCol = 0
                wrapPending = false
            }
            0x0E -> useG1 = true
            0x0F -> useG1 = false
            0x1B -> state = ESC
            else -> Unit // BEL e o resto: ignora
        }
    }

    private fun escByte(b: Int) {
        state = GROUND
        when (b) {
            '['.code -> {
                state = CSI
                paramCount = 0
                curParam = -1
                privMarker = 0
                intermediate = 0
            }
            ']'.code, 'P'.code, 'X'.code, '^'.code, '_'.code -> state = STR
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'E'.code -> {
                cursorCol = 0
                lineFeed()
            }
            'M'.code -> {
                wrapPending = false
                if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
            }
            'H'.code -> if (cursorCol < cols) tabStops[cursorCol] = true
            'c'.code -> resetLocked()
            '('.code, ')'.code, '*'.code, '+'.code, '#'.code -> {
                escIntermediate = b
                state = ESC_INT
            }
            0x1B -> state = ESC
            else -> Unit // '=' '>' e desconhecidos
        }
    }

    private fun designateCharset(b: Int) {
        val graphics = (b == '0'.code)
        when (escIntermediate) {
            '('.code -> g0Graphics = graphics
            ')'.code -> g1Graphics = graphics
            else -> Unit
        }
    }

    /** Textos de OSC/DCS/etc.: só consome até o terminador (BEL ou ESC \). */
    private fun strByte(b: Int) {
        when (b) {
            0x07 -> state = GROUND
            0x1B -> state = STR_ESC
            else -> Unit
        }
    }

    private fun csiByte(b: Int) {
        when {
            b in 0x30..0x39 -> curParam = minOf((if (curParam < 0) 0 else curParam) * 10 + (b - 0x30), 100000)
            b == 0x3B || b == 0x3A -> pushParam()
            b in 0x3C..0x3F -> if (paramCount == 0 && curParam < 0 && privMarker == 0) privMarker = b
            b in 0x20..0x2F -> intermediate = b
            b in 0x40..0x7E -> {
                if (curParam >= 0 || paramCount > 0) pushParam()
                state = GROUND
                csiDispatch(b.toChar())
            }
            b == 0x1B -> state = ESC
            b == 0x18 || b == 0x1A -> state = GROUND
            b < 0x20 -> control(b)
            else -> state = GROUND
        }
    }

    private fun pushParam() {
        if (paramCount < params.size) {
            params[paramCount] = if (curParam < 0) 0 else curParam
            paramCount++
        }
        curParam = -1
    }

    /** Parâmetro [i], ou [def] se ausente ou zero (comportamento dos movimentos de cursor). */
    private fun arg(i: Int, def: Int): Int {
        val v = if (i < paramCount) params[i] else 0
        return if (v <= 0) def else v
    }

    /** Parâmetro [i] como veio (ausente = 0). */
    private fun raw(i: Int): Int = if (i < paramCount) params[i] else 0

    private fun csiDispatch(f: Char) {
        if (intermediate != 0) return
        if (privMarker == '?'.code) {
            if (f == 'h' || f == 'l') privateModes(f == 'h')
            return
        }
        if (privMarker == '>'.code) {
            if (f == 'c') reply("\u001B[>0;0;0c")
            return
        }
        if (privMarker != 0) return

        when (f) {
            'A' -> {
                cursorRow = maxOf(0, cursorRow - arg(0, 1))
                wrapPending = false
            }
            'B', 'e' -> {
                cursorRow = minOf(rows - 1, cursorRow + arg(0, 1))
                wrapPending = false
            }
            'C', 'a' -> {
                cursorCol = minOf(cols - 1, cursorCol + arg(0, 1))
                wrapPending = false
            }
            'D' -> {
                cursorCol = maxOf(0, cursorCol - arg(0, 1))
                wrapPending = false
            }
            'E' -> {
                cursorRow = minOf(rows - 1, cursorRow + arg(0, 1))
                cursorCol = 0
                wrapPending = false
            }
            'F' -> {
                cursorRow = maxOf(0, cursorRow - arg(0, 1))
                cursorCol = 0
                wrapPending = false
            }
            'G', '`' -> {
                cursorCol = (arg(0, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'd' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                wrapPending = false
            }
            'J' -> eraseDisplay(raw(0))
            'K' -> eraseLine(raw(0))
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            '@' -> insertChars(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            'X' -> grid[cursorRow].clear(cursorCol, cursorCol + arg(0, 1), curBg)
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'm' -> sgr()
            'r' -> {
                val top = arg(0, 1) - 1
                val bottom = arg(1, rows) - 1
                if (top >= 0 && bottom < rows && top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = 0
                    cursorCol = 0
                    wrapPending = false
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> when (raw(0)) {
                5 -> reply("\u001B[0n")
                6 -> reply("\u001B[${cursorRow + 1};${cursorCol + 1}R")
                else -> Unit
            }
            'c' -> if (raw(0) == 0) reply("\u001B[?1;2c")
            'h', 'l' -> {
                for (i in 0 until maxOf(1, paramCount)) {
                    if (raw(i) == 4) insertMode = (f == 'h')
                }
            }
            else -> Unit
        }
    }

    private fun privateModes(set: Boolean) {
        for (i in 0 until paramCount) {
            when (params[i]) {
                1 -> appCursorKeys = set
                7 -> autoWrap = set
                25 -> cursorVisible = set
                47, 1047 -> if (set) enterAlt(false) else leaveAlt(false)
                1049 -> if (set) enterAlt(true) else leaveAlt(true)
                2004 -> bracketedPaste = set
                else -> Unit // mouse, origem, etc.: ignora
            }
        }
    }

    private fun enterAlt(save: Boolean) {
        if (altScreen) return
        if (save) saveCursor()
        savedMain = grid
        grid = Array(rows) { Row(cols) }
        altScreen = true
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private fun leaveAlt(restore: Boolean) {
        if (!altScreen) return
        grid = savedMain ?: Array(rows) { Row(cols) }
        savedMain = null
        altScreen = false
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        if (restore) restoreCursor()
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedFg = curFg
        savedBg = curBg
        savedAttr = curAttr
    }

    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        curFg = savedFg
        curBg = savedBg
        curAttr = savedAttr
        wrapPending = false
    }

    private fun sgr() {
        if (paramCount == 0) {
            curFg = 0
            curBg = 0
            curAttr = 0
            return
        }
        var i = 0
        while (i < paramCount) {
            val v = params[i]
            when {
                v == 0 -> {
                    curFg = 0
                    curBg = 0
                    curAttr = 0
                }
                v == 1 -> curAttr = curAttr or BOLD
                v == 2 -> curAttr = curAttr or DIM
                v == 3 -> curAttr = curAttr or ITALIC
                v == 4 -> curAttr = curAttr or UNDERLINE
                v == 7 -> curAttr = curAttr or REVERSE
                v == 22 -> curAttr = curAttr and (BOLD or DIM).inv()
                v == 23 -> curAttr = curAttr and ITALIC.inv()
                v == 24 -> curAttr = curAttr and UNDERLINE.inv()
                v == 27 -> curAttr = curAttr and REVERSE.inv()
                v in 30..37 -> curFg = PALETTE[v - 30]
                v == 39 -> curFg = 0
                v in 40..47 -> curBg = PALETTE[v - 40]
                v == 49 -> curBg = 0
                v in 90..97 -> curFg = PALETTE[8 + v - 90]
                v in 100..107 -> curBg = PALETTE[8 + v - 100]
                v == 38 || v == 48 -> {
                    val mode = if (i + 1 < paramCount) params[i + 1] else 0
                    var color = 0
                    if (mode == 5 && i + 2 < paramCount) {
                        color = PALETTE[params[i + 2].coerceIn(0, 255)]
                        i += 2
                    } else if (mode == 2 && i + 4 < paramCount) {
                        val r = params[i + 2] and 0xFF
                        val g = params[i + 3] and 0xFF
                        val b = params[i + 4] and 0xFF
                        color = (0xFF000000).toInt() or (r shl 16) or (g shl 8) or b
                        i += 4
                    } else {
                        i = paramCount // sequência incompleta: ignora o resto
                    }
                    if (color != 0) {
                        if (v == 38) curFg = color else curBg = color
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    // ----- escrita e rolagem -----

    private fun putChar(cpIn: Int) {
        var cp = cpIn
        if (cp > 0x10FFFF || cp in 0xD800..0xDFFF) cp = 0xFFFD
        val graphics = if (useG1) g1Graphics else g0Graphics
        if (graphics && cp in 0x5F..0x7E) cp = LINE_DRAWING[cp - 0x5F]
        // Caracteres de largura zero (acentos soltos, ZWJ, seletores): ignora em vez de ocupar uma célula.
        if (cp in 0x0300..0x036F || cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F) return

        if (wrapPending) {
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            }
            wrapPending = false
        }
        if (insertMode) insertChars(1)
        val row = grid[cursorRow]
        row.chars[cursorCol] = cp
        row.fg[cursorCol] = curFg
        row.bg[cursorCol] = curBg
        row.attr[cursorCol] = curAttr
        if (cursorCol < cols - 1) {
            cursorCol++
        } else if (autoWrap) {
            wrapPending = true
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun blankRow(): Row {
        val r = Row(cols)
        if (curBg != 0) r.clear(0, cols, curBg)
        return r
    }

    private fun pushScrollback(row: Row) {
        scrollback.addLast(row)
        if (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun scrollUp(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            val removed = grid[scrollTop]
            if (!altScreen && scrollTop == 0) pushScrollback(removed)
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow()
        }
    }

    private fun scrollDown(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = blankRow()
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = minOf(n, scrollBottom - cursorRow + 1)
        repeat(count) {
            for (r in scrollBottom downTo cursorRow + 1) grid[r] = grid[r - 1]
            grid[cursorRow] = blankRow()
        }
        cursorCol = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = minOf(n, scrollBottom - cursorRow + 1)
        repeat(count) {
            for (r in cursorRow until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow()
        }
        cursorCol = 0
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val count = minOf(n, cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + count) row.copyCell(c, c - count)
        row.clear(cursorCol, cursorCol + count, curBg)
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val count = minOf(n, cols - cursorCol)
        for (c in cursorCol until cols - count) row.copyCell(c, c + count)
        row.clear(cols - count, cols, curBg)
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                grid[cursorRow].clear(cursorCol, cols, curBg)
                for (r in cursorRow + 1 until rows) grid[r].clear(0, cols, curBg)
            }
            1 -> {
                for (r in 0 until cursorRow) grid[r].clear(0, cols, curBg)
                grid[cursorRow].clear(0, cursorCol + 1, curBg)
            }
            2 -> for (r in 0 until rows) grid[r].clear(0, cols, curBg)
            3 -> scrollback.clear()
            else -> Unit
        }
    }

    private fun eraseLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> row.clear(cursorCol, cols, curBg)
            1 -> row.clear(0, cursorCol + 1, curBg)
            2 -> row.clear(0, cols, curBg)
            else -> Unit
        }
    }

    companion object {
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val REVERSE = 16

        private const val GROUND = 0
        private const val ESC = 1
        private const val ESC_INT = 2
        private const val CSI = 3
        private const val STR = 4
        private const val STR_ESC = 5

        /** Paleta de 256 cores (as 16 primeiras ao estilo do VS Code). Cada cor tem alfa 0xFF. */
        val PALETTE: IntArray = buildPalette()

        private fun buildPalette(): IntArray {
            val base = intArrayOf(
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x3B78FF, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x5C9CFF, 0xD670D6, 0x29B8DB, 0xFFFFFF
            )
            val opaque = 0xFF000000.toInt()
            val p = IntArray(256)
            for (i in 0 until 16) p[i] = opaque or base[i]
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 0 until 216) {
                val r = levels[i / 36]
                val g = levels[(i / 6) % 6]
                val b = levels[i % 6]
                p[16 + i] = opaque or (r shl 16) or (g shl 8) or b
            }
            for (i in 0 until 24) {
                val v = 8 + 10 * i
                p[232 + i] = opaque or (v shl 16) or (v shl 8) or v
            }
            return p
        }

        /** Traços de caixa do conjunto DEC (ESC ( 0), de 0x5F a 0x7E. */
        private val LINE_DRAWING = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7
        )
    }
}
