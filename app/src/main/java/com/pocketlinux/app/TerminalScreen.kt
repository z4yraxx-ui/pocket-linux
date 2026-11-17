package com.pocketlinux.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Caractere de controle para Ctrl+tecla (Ctrl+C = 0x03), ou null se a tecla não tem. */
private fun controlChar(ch: Char): Char? = when (ch) {
    in 'a'..'z' -> (ch.code - 'a'.code + 1).toChar()
    in 'A'..'Z' -> (ch.code - 'A'.code + 1).toChar()
    '[' -> '\u001B'
    '\\' -> '\u001C'
    ']' -> '\u001D'
    '^' -> '\u001E'
    '_' -> '\u001F'
    ' ', '@' -> '\u0000'
    '?' -> '\u007F'
    else -> null
}

private fun arrowSeq(dir: Char, appMode: Boolean): String =
    if (appMode) "\u001BO$dir" else "\u001B[$dir"

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val running by PocketSession.isRunning.collectAsState()
    val terminal = PocketSession.terminal
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    var scrollBack by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }

    // Abrir a tela já liga o Debian (não faz nada se já estiver rodando ou não instalado).
    LaunchedEffect(Unit) { PocketSession.startShell() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
            keyboard?.show()
        } catch (e: Exception) {
            // sem foco ainda: o toque na tela abre o teclado
        }
    }

    fun send(text: String) {
        scrollBack = 0
        PocketSession.writeInput(text)
    }

    /** Texto digitado no teclado virtual; aplica Ctrl/Alt se estiverem armados na barra. */
    fun sendTyped(text: String) {
        val sb = StringBuilder()
        for (ch in text) {
            var out = if (ch == '\n') "\r" else ch.toString()
            if (ctrl) {
                val c = controlChar(ch)
                if (c != null) out = c.toString()
                ctrl = false
            }
            if (alt) {
                out = "\u001B$out"
                alt = false
            }
            sb.append(out)
        }
        send(sb.toString())
    }

    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        val app = terminal.read { it.appCursorKeys }
        val seq: String? = when (ev.key) {
            Key.Enter, Key.NumPadEnter -> "\r"
            Key.Backspace -> "\u007F"
            Key.Tab -> "\t"
            Key.Escape -> "\u001B"
            Key.DirectionUp -> arrowSeq('A', app)
            Key.DirectionDown -> arrowSeq('B', app)
            Key.DirectionRight -> arrowSeq('C', app)
            Key.DirectionLeft -> arrowSeq('D', app)
            Key.MoveHome -> "\u001B[H"
            Key.MoveEnd -> "\u001B[F"
            Key.PageUp -> "\u001B[5~"
            Key.PageDown -> "\u001B[6~"
            Key.Delete -> "\u001B[3~"
            Key.Insert -> "\u001B[2~"
            else -> null
        }
        if (seq != null) {
            send(seq)
            return true
        }
        val cp = ev.utf16CodePoint
        if (ev.isCtrlPressed && cp in 1..0xFFFF) {
            val c = controlChar(cp.toChar())
            if (c != null) {
                send(c.toString())
                return true
            }
        }
        if (ev.isAltPressed && cp > 32) {
            send("\u001B" + String(Character.toChars(cp)))
            return true
        }
        return false
    }

    fun openKeyboard() {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // ignora
        }
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Mint)
            }
            Text("Terminal", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val small = PaddingValues(horizontal = 8.dp)
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(terminal.transcript())) },
                contentPadding = small
            ) { Text("Copiar", color = Mint) }
            TextButton(
                onClick = {
                    val t = clipboard.getText()?.text
                    if (!t.isNullOrEmpty()) {
                        scrollBack = 0
                        PocketSession.pasteText(t)
                    }
                },
                enabled = running,
                contentPadding = small
            ) { Text("Colar", color = Mint) }
            if (running) {
                TextButton(onClick = { PocketSession.stopShell() }, contentPadding = small) {
                    Text("Encerrar", color = Mint)
                }
            } else {
                TextButton(onClick = { PocketSession.startShell() }, contentPadding = small) {
                    Text("Iniciar", color = Mint)
                }
            }
        }

        TerminalCanvas(
            terminal = terminal,
            scrollBack = scrollBack,
            onScrollBy = { d ->
                val max = terminal.read { it.scrollbackLineCount() }
                scrollBack = (scrollBack + d).coerceIn(0, max)
            },
            onResize = { rows, cols -> PocketSession.resizeTerminal(rows, cols) },
            onTap = { openKeyboard() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        )

        KeyBar(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl },
            onAlt = { alt = !alt },
            onText = { send(it) },
            onArrow = { dir -> send(arrowSeq(dir, terminal.read { it.appCursorKeys })) }
        )

        // Campo invisível que recebe o teclado virtual. Guarda sempre um espaço de sentinela:
        // texto a mais = caracteres digitados; texto a menos = backspace.
        Box(Modifier.size(1.dp)) {
            BasicTextField(
                value = field,
                onValueChange = { nv ->
                    val t = nv.text
                    if (t.isEmpty()) {
                        send("\u007F")
                    } else {
                        val typed = if (t.startsWith(" ")) t.substring(1) else t
                        if (typed.isNotEmpty()) sendTyped(typed)
                    }
                    field = TextFieldValue(" ", TextRange(1))
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { handleKey(it) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.None
                ),
                cursorBrush = SolidColor(Color.Transparent)
            )
        }
    }
}

/** Teclas que o teclado do Android não tem (Esc, Tab, Ctrl, setas, etc.). */
@Composable
private fun KeyBar(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onText: (String) -> Unit,
    onArrow: (Char) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        KeyChip("Esc") { onText("\u001B") }
        KeyChip("Tab") { onText("\t") }
        KeyChip("Ctrl", active = ctrl) { onCtrl() }
        KeyChip("Alt", active = alt) { onAlt() }
        KeyChip("◀") { onArrow('D') }
        KeyChip("▼") { onArrow('B') }
        KeyChip("▲") { onArrow('A') }
        KeyChip("▶") { onArrow('C') }
        KeyChip("Home") { onText("\u001B[H") }
        KeyChip("End") { onText("\u001B[F") }
        KeyChip("PgUp") { onText("\u001B[5~") }
        KeyChip("PgDn") { onText("\u001B[6~") }
        KeyChip("^C") { onText("\u0003") }
        KeyChip("^D") { onText("\u0004") }
        KeyChip("|") { onText("|") }
        KeyChip("/") { onText("/") }
        KeyChip("-") { onText("-") }
        KeyChip("~") { onText("~") }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (active) Mint else Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (active) Color(0xFF0B0F1A) else Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
