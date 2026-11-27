package de.trailscape.app.indoor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GarageOrbitCameraTest {

    @Test
    fun `Startansicht zeigt schraeg von vorn auf die Antriebsseite und dreht sich sanft`() {
        val cam = GarageOrbitCamera()
        assertEquals(GarageOrbitCamera.DEFAULT_YAW_DEG, cam.yawDeg)
        assertEquals(GarageOrbitCamera.DEFAULT_PITCH_DEG, cam.pitchDeg)
        assertTrue(cam.autoRotate)

        val startPose = cam.pose(aspect = 16.0 / 10.0)
        // Antrieb (-X) und vorn (+Z) im Blick.
        assertTrue(startPose.eye.x < 0.0, "Startkamera steht auf der Antriebsseite (-X)")
        assertTrue(startPose.eye.z > 0.0, "Startkamera blickt von vorn (+Z)")
        assertTrue(startPose.eye.y > startPose.target.y, "Startkamera schaut leicht von oben")

        val yawBefore = cam.yawDeg
        cam.tick(0.5)
        assertTrue(cam.yawDeg > yawBefore, "sanfte Eigendrehung dreht weiter")
    }

    @Test
    fun `Ziehen stoppt die Eigendrehung, dreht um 360 Grad und Doppeltipp setzt zurueck`() {
        val cam = GarageOrbitCamera()
        cam.onDrag(deltaXDp = 400f)
        assertFalse(cam.autoRotate, "Nutzerzug stoppt Eigendrehung")
        val yawAfterDrag = cam.yawDeg
        cam.tick(1.0)
        assertEquals(yawAfterDrag, cam.yawDeg, "nach Nutzerzug keine automatische Drehung")

        // Volle Drehung ueber +-180 Grad bleibt sauber normiert.
        cam.onDrag(deltaXDp = -1000f)
        assertTrue(cam.yawDeg >= -180.0 && cam.yawDeg < 180.0)

        cam.reset()
        assertEquals(GarageOrbitCamera.DEFAULT_YAW_DEG, cam.yawDeg)
        assertEquals(GarageOrbitCamera.DEFAULT_PITCH_DEG, cam.pitchDeg)
        assertTrue(cam.autoRotate)
    }

    @Test
    fun `Framing passt Abstand an schmales Telefon, S25 und Tablet quer an`() {
        val cam = GarageOrbitCamera()
        val narrowDist = (cam.pose(0.6).eye - cam.pose(0.6).target).length()
        val wideDist = (cam.pose(1.6).eye - cam.pose(1.6).target).length()
        assertTrue(narrowDist > wideDist, "Schmales Fenster rueckt weiter weg, damit das Rad ganz passt")
        assertTrue(narrowDist in (2.8 - 1e-6)..(5.6 + 1e-6), "narrowDist=$narrowDist")
        assertTrue(wideDist in (2.8 - 1e-6)..(5.6 + 1e-6), "wideDist=$wideDist")
    }
}
