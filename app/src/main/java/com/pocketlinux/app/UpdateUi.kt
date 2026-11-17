package com.pocketlinux.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Caixas de diálogo do botão "Atualizar". A saída do apt aparece no terminal. */
@Composable
internal fun UpdateDialog(onOpenTerminal: () -> Unit) {
    val state by PocketSession.updateState.collectAsState()

    when (val s = state) {
        is UpdateState.Idle -> Unit

        is UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Buscando atualizações") },
            text = {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Consultando os repositórios do Debian. A saída aparece no terminal.")
                }
            },
            confirmButton = { TextButton(onClick = onOpenTerminal) { Text("Ver terminal", color = Mint) } }
        )

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { PocketSession.dismissUpdate() },
            title = { Text(if (s.count == 1) "1 pacote para atualizar" else "${s.count} pacotes para atualizar") },
            text = {
                Text(
                    "Deixe a tela ligada durante a atualização. Com a tela apagada o Android pode " +
                        "pausar o processo e a atualização pode travar no meio."
                )
            },
            confirmButton = {
                TextButton(onClick = { PocketSession.applyUpdates() }) { Text("Atualizar agora", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = { PocketSession.dismissUpdate() }) { Text("Depois", color = Muted) }
            }
        )

        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { PocketSession.dismissUpdate() },
            title = { Text("Tudo atualizado") },
            text = { Text("Não há pacotes para atualizar.") },
            confirmButton = {
                TextButton(onClick = { PocketSession.dismissUpdate() }) { Text("Ok", color = Mint) }
            }
        )

        is UpdateState.Upgrading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Atualizando o Debian") },
            text = {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Mantenha a tela ligada. Acompanhe o progresso no terminal.")
                }
            },
            confirmButton = { TextButton(onClick = onOpenTerminal) { Text("Ver terminal", color = Mint) } }
        )

        is UpdateState.Done -> AlertDialog(
            onDismissRequest = { PocketSession.dismissUpdate() },
            title = { Text("Atualização concluída") },
            text = { Text("Os pacotes foram atualizados.") },
            confirmButton = {
                TextButton(onClick = { PocketSession.dismissUpdate() }) { Text("Ok", color = Mint) }
            }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { PocketSession.dismissUpdate() },
            title = { Text("Não deu certo") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = onOpenTerminal) { Text("Ver terminal", color = Mint) } },
            dismissButton = {
                TextButton(onClick = { PocketSession.dismissUpdate() }) { Text("Fechar", color = Muted) }
            }
        )
    }
}
