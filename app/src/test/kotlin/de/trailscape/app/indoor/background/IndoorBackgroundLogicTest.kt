package de.trailscape.app.indoor.background

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.indoor.IndoorRuntimeState
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.indoor.IndoorRenderUi
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerConnection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Benachrichtigung und Startbedingung des Indoor-Dienstes (docs/indoor/hintergrund.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorBackgroundLogicTest {

    private fun runtimeState(phase: IndoorPhase?, powerW: Int? = 185, finished: Boolean = false) = IndoorRuntimeState(
        session = phase?.let {
            IndoorSessionState(
                phase = it, mode = IndoorMode.SIM, nowMs = 0L, elapsedS = 2_530, distM = 12_340.0, routeDistM = 2_340.0,
                lap = 2, speedMps = 8.0, powerW = powerW, cadenceRpm = 85, hrBpm = 130, gradePct = 1.0,
                surface = SurfaceKind.ASPHALT, crr = 0.004, headwindMps = 0.0, gear = GearState(12, 24, 2.0),
                ghost = null, erg = null,
                totals = IndoorTotals(12_340.0, 80.0, 2_400, 180.0, null, 85.0, 130.0, 400.0),
            )
        },
        trainer = TrainerConnection.READY,
        capabilities = TrainerCapabilities.NONE,
        shifter = null,
        shifterBatteryPct = null,
        render = IndoorRenderUi.NoWorld,
        notice = null,
        problem = null,
        finished = finished,
    )

    @Test
    fun `ab Android 14 braucht der Dienst die Bluetooth-Freigabe`() {
        assertTrue(IndoorBackgroundLogic.canStartService(33, hasBluetoothConnect = false))
        assertTrue(IndoorBackgroundLogic.canStartService(34, hasBluetoothConnect = true))
        assertFalse(IndoorBackgroundLogic.canStartService(34, hasBluetoothConnect = false))
        assertFalse(IndoorBackgroundLogic.canStartService(36, hasBluetoothConnect = false))
    }

    @Test
    fun `Zustand der Benachrichtigung je Phase`() {
        val riding = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.RUNNING))
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.RIDING, riding.kind)
        assertEquals(185, riding.powerW)
        assertFalse(riding.showResume)

        val paused = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.PAUSED))
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.PAUSED, paused.kind)
        assertNull(paused.powerW, "in der Pause keine Leistung")
        assertTrue(paused.showResume)

        val auto = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.AUTO_PAUSED))
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.PAUSED, auto.kind)
        assertFalse(auto.showResume, "die Auto-Pause endet mit dem naechsten Tritt")

        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.READY, IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.READY)).kind)
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.READY, IndoorBackgroundLogic.modelOf(runtimeState(null)).kind)
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.FINISHING, IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.RUNNING, finished = true)).kind)
        assertEquals(IndoorBackgroundLogic.NotificationModel.Kind.FINISHING, IndoorBackgroundLogic.modelOf(null).kind)
    }

    @Test
    fun `Texte zeigen Leistung, Zeit und Distanz`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val m = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.RUNNING))
        assertEquals("Indoor · Hausrunde", IndoorBackgroundLogic.title(res, "Hausrunde", m))
        assertEquals("185 W · 42:10 · 12,3 km", IndoorBackgroundLogic.text(res, AppLanguage.DE, m))

        val p = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.PAUSED))
        assertEquals("Pausiert · Hausrunde", IndoorBackgroundLogic.title(res, "Hausrunde", p))
        assertEquals("– W · 42:10 · 12,3 km", IndoorBackgroundLogic.text(res, AppLanguage.DE, p))
    }

    @Test
    @Config(qualifiers = "en-rGB")
    fun `englische Texte`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val m = IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.RUNNING))
        assertEquals("185 W · 42:10 · 12.3 km", IndoorBackgroundLogic.text(res, AppLanguage.EN, m))
        assertEquals("Paused · Loop", IndoorBackgroundLogic.title(res, "Loop", IndoorBackgroundLogic.modelOf(runtimeState(IndoorPhase.PAUSED))))
    }
}
