package com.pocketlinux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Isenção de otimização de bateria: sem ela, o Android (e a Samsung) podem encerrar o Debian. */
object BatteryOptimization {
    private const val PREFS = "pocketlinux"
    private const val KEY_ASKED = "battery_asked"

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Pergunta só uma vez, e só se ainda não estiver isento. */
    fun shouldAsk(context: Context): Boolean {
        if (isIgnoring(context)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** Abre o diálogo do sistema para isentar o app. Se o aparelho não tiver, cai na lista geral. */
    fun request(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // Sem tela de bateria neste aparelho: segue sem a isenção.
            }
        }
    }
}
