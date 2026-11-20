package com.pocketlinux.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cartão do desktop na tela inicial: liga, mostra e desliga o LXQt. */
@Composable
internal fun DesktopCard(
    state: DesktopState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyLog: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DESKTOP", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    when (state) {
                        is DesktopState.Stopped -> "Desligado"
                        is DesktopState.Starting -> "Iniciando"
                        is DesktopState.Running -> "Ligado"
                        is DesktopState.Failed -> "Erro"
                    }
                )
            }
            Text("LXQt na tela", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)

            when (state) {
                is DesktopState.Stopped -> {
                    Text(
                        "Abre o desktop do Debian em tela cheia, com mouse e teclado. " +
                            "Ele roda direto no servidor X do app, sem VNC.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Abrir desktop") }
                }

                is DesktopState.Starting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${state.step}…", color = Muted, fontSize = 13.sp)
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                }

                is DesktopState.Running -> {
                    Text(
                        "O desktop está rodando. Se você saiu da tela dele, toque em Mostrar desktop.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Mostrar desktop") }
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text("Encerrar desktop")
                    }
                }

                is DesktopState.Failed -> {
                    Text(
                        state.message,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
                    TextButton(onClick = onCopyLog) { Text("Copiar log completo", color = Mint) }
                }
            }
        }
    }
}
