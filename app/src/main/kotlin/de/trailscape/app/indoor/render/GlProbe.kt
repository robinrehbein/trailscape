package de.trailscape.app.indoor.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Fragt Hersteller, Renderer und Version des Grafiktreibers ab — fuer die
 * Diagnoseseite und Fehlerberichte (`RenderDeviceInfo`). Dazu ein winziger,
 * eigener EGL-Kontext (OpenGL ES 3, 1×1-Pbuffer), der gleich wieder
 * verschwindet. **Vor** dem Start der Engine aufrufen und `eglTerminate`
 * bewusst nie: Die EGL-Anzeige ist prozessweit, Filament benutzt dieselbe.
 * Scheitert etwas, kommt `null` — die Darstellung startet trotzdem.
 */
internal object GlProbe {
    data class Result(val vendor: String, val renderer: String, val version: String)

    @Volatile
    private var cached: Result? = null

    fun query(): Result? {
        cached?.let { return it }
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) return null
            val config = configs[0] ?: return null
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val r = Result(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "",
                renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "",
                version = GLES20.glGetString(GLES20.GL_VERSION) ?: "",
            )
            cached = r
            return r
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                }
            }
        }
    }

    /** `EGL_OPENGL_ES3_BIT_KHR` (EGLExt ab API 18; hier als Wert, um keine Klasse vorauszusetzen). */
    private const val EGL_OPENGL_ES3_BIT = 0x00000040
}
