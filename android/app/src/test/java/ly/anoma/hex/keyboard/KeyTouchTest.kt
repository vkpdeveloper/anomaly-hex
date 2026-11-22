package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a letter touch types when fingers move fast: a tap never turns into a glide or a neighbour's
 * letter unless it really slid or landed between keys. Keys are [PITCH] wide. */
class KeyTouchTest {
    private fun at(x: Double, y: Double = 0.0) = KeyPoint(x, y)
    private fun trail() = GlideTrail(0, 1f)

    @Test fun aQuickTapThatDriftsTowardTheNextKeyStaysATap() {
        // "vkp" typed fast: the thumb is already heading for p as it lifts off k.
        val g = trail(); val k = Any()
        g.down(k, at(0.0), 1000, true, PITCH)
        assertFalse(g.move(k, at(30.0, -10.0), 1040, false))
        assertFalse(g.move(k, at(60.0, -25.0), 1070, false))
        assertFalse(g.active)
        assertNull(g.up(k, 1090))
    }

    @Test fun aDeliberateDragGlides() {
        val g = trail(); val key = Any()
        g.down(key, at(0.0), 1000, true, PITCH)
        var started = false
        for (i in 1..10) started = g.move(key, at(i * 30.0), 1000L + i * 30, false) || started
        assertTrue(started)
        val path = g.up(key, 1310)
        assertNotNull(path)
        assertEquals(11, path!!.size)
    }

    @Test fun aKeyHeldLongEnoughGlidesAfterTheUsualDistance() {
        val g = trail(); val key = Any()
        g.down(key, at(0.0), 1000, true, PITCH)
        assertTrue(g.move(key, at(50.0), 1000 + GlideTrail.SETTLE_MS, false))
    }

    @Test fun aKeyDownWhileTheLastIsStillHeldIsTyping() {
        // Rollover: k goes down before v lifts, so v never lifted as the drag's owner. k's later drift
        // is typing, not the start of a glide.
        val g = trail(); val v = Any(); val k = Any()
        g.down(v, at(0.0), 1000, true, PITCH)
        g.down(k, at(500.0), 1060, true, PITCH)
        assertNull(g.up(v, 1090))
        assertFalse(g.move(k, at(600.0), 1600, false))
        assertTrue(g.move(k, at(500.0 + PITCH * GlideTrail.START_KEYS_TYPING), 1650, false))
    }

    @Test fun theGlideDistanceEasesFromTapToSettled() {
        assertEquals(GlideTrail.START_KEYS_TAP, GlideTrail.startKeys(0, typing = false), 1e-9)
        assertEquals(GlideTrail.START_KEYS, GlideTrail.startKeys(GlideTrail.SETTLE_MS, typing = false), 1e-9)
        assertTrue(GlideTrail.startKeys(GlideTrail.SETTLE_MS / 2, typing = false) > GlideTrail.START_KEYS)
        assertEquals(GlideTrail.START_KEYS_TYPING, GlideTrail.startKeys(10_000, typing = true), 1e-9)
    }

    // k's touch area runs 0..40 by 0..50 with its keycap drawn at 2..38 by 5..45; l is to its right
    // and o above it.
    private val face = KeyRect(2.0, 5.0, 38.0, 45.0)
    private val areas = mapOf('l' to KeyRect(40.0, 0.0, 80.0, 50.0), 'o' to KeyRect(20.0, -50.0, 60.0, 0.0))

    @Test fun aTouchOnTheKeycapNeverGoesToANeighbour() {
        assertTrue(nearKeys(at(37.0, 25.0), face, areas, 5.0).isEmpty())
        assertTrue(nearKeys(at(30.0, 6.0), face, areas, 5.0).isEmpty())
    }

    @Test fun aTouchInTheGapMayGoToTheNearestNeighbours() {
        assertEquals(mapOf('l' to 1.0), nearKeys(at(39.0, 25.0), face, areas, 5.0))
        assertEquals(mapOf('o' to 3.0), nearKeys(at(30.0, 3.0), face, areas, 5.0))
        assertTrue(nearKeys(at(10.0, 25.0), KeyRect(12.0, 5.0, 38.0, 45.0), areas, 5.0).isEmpty())
    }

    private companion object {
        const val PITCH = 100.0
    }
}
