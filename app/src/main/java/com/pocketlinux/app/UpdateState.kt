package com.pocketlinux.app

/** Estado do botão "Atualizar" (apt update + apt upgrade dentro do Debian). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val count: Int) : UpdateState
    data object UpToDate : UpdateState
    data object Upgrading : UpdateState
    data object Done : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Verdadeiro enquanto a atualização está rodando (a tela deve ficar ligada). */
fun UpdateState.isBusy(): Boolean = this is UpdateState.Checking || this is UpdateState.Upgrading
