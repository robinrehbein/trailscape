package de.trailscape.app.indoor.render

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Waechter gegen native Abstuerze der Darstellung.
 *
 * Filament beendet den Prozess hart, wenn der Grafiktreiber ein Shaderprogramm
 * ablehnt (`ShaderCompilerService`: „failed to link or compile") — kein
 * `try/catch` faengt das. Deshalb merkt sich der Waechter in einer Datei, dass
 * gerade eine Welt geladen und gezeichnet wird ([arm]); endet das ordentlich,
 * verschwindet die Datei wieder ([disarm]). Liegt sie beim naechsten Start noch
 * da, ist der Prozess mitten in der Welt gestorben: Ab dann gilt der **sichere
 * Modus** — ohne eigene Weltmaterialien (Boden, Laub, Wege, Wasser) und ohne
 * Voegel und andere Radfahrer, also die Darstellung vor den Grafik-Paketen.
 *
 * Der sichere Modus gilt je App-Version (ein Update darf es neu versuchen) und
 * laesst sich auf der Diagnoseseite zuruecksetzen.
 */
internal class RenderGuard(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "indoor")
    private val guardFile = File(dir, "render-guard")
    private val safeFile = File(dir, "render-safe")
    private val version: String = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(p).toString()
    }.getOrDefault("0")

    /** Steht beim Erzeugen fest und aendert sich nur durch [reset]. */
    var safeMode: Boolean = false
        private set

    init {
        runCatching {
            if (guardFile.isFile) {
                dir.mkdirs()
                safeFile.writeText(version)
                guardFile.delete()
            }
            safeMode = safeFile.isFile && safeFile.readText().trim() == version
            if (!safeMode && safeFile.isFile) safeFile.delete()
        }
    }

    /** Eine Welt wird geladen oder gezeichnet. */
    fun arm() {
        if (safeMode) return
        runCatching {
            dir.mkdirs()
            if (!guardFile.isFile) guardFile.writeText(version)
        }
    }

    /** Ordentlich beendet oder pausiert. */
    fun disarm() {
        runCatching { if (guardFile.isFile) guardFile.delete() }
    }

    /** Sicheren Modus aufheben; wirkt auf die naechste erzeugte Darstellung. */
    fun reset() {
        runCatching {
            safeFile.delete()
            guardFile.delete()
        }
        safeMode = false
    }

    companion object {
        fun isSafeMode(context: Context): Boolean = RenderGuard(context).safeMode
        fun reset(context: Context) = RenderGuard(context).reset()
    }
}
