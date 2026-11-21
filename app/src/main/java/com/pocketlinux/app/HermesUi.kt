package com.pocketlinux.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** Cartão do Hermes na tela inicial: instala, abre no terminal e confere a instalação. */
@Composable
internal fun HermesCard(
    state: HermesState,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDoctor: () -> Unit,
    onCopyLog: () -> Unit,
    onDismissError: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HERMES", color = Mint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    when (state) {
                        is HermesState.NotInstalled -> "Não instalado"
                        is HermesState.Installing -> "Instalando"
                        is HermesState.Ready -> if (state.checking) "Verificando" else "Instalado"
                        is HermesState.Failed -> "Erro"
                    }
                )
            }
            Text("Hermes Agent", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)

            when (state) {
                is HermesState.NotInstalled -> {
                    Text(
                        "Instala o Hermes Agent (Nous Research) dentro do Debian, com Python e pip. " +
                            "Use Wi-Fi: o download é grande e pode levar vários minutos.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Instalar Hermes") }
                }

                is HermesState.Installing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        state.line,
                        color = Muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Pode sair do app: a instalação continua em segundo plano.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }

                is HermesState.Ready -> {
                    Text(
                        "Abre o terminal já com o Hermes rodando. Na primeira vez, rode " +
                            "\"hermes setup\" para escolher o modelo e entrar na conta.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Abrir Hermes") }
                    OutlinedButton(
                        onClick = onDoctor,
                        enabled = !state.checking,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.checking) "Verificando…" else "Diagnóstico do Hermes") }
                    if (state.checking) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (state.report != null) {
                        Surface(color = Color(0xFF141B29), shape = RoundedCornerShape(12.dp)) {
                            Text(
                                state.report,
                                color = Muted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }
                }

                is HermesState.Failed -> {
                    Text(
                        state.message,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Tentar de novo") }
                    TextButton(onClick = onCopyLog) { Text("Copiar log completo", color = Mint) }
                    TextButton(onClick = onDismissError) { Text("Fechar aviso", color = Muted) }
                }
            }
        }
    }
}
