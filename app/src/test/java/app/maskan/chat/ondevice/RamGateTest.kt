package app.maskan.chat.ondevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone is in a model's RAM class when it reports 85% of it (2.7 plan item 15). */
class RamGateTest {

    private fun gib(value: Double) = (value * RamGate.GIB).toLong()

    /** MemTotal as /proc/meminfo prints it, in kB. */
    private fun memTotalKb(kb: Long) = kb * 1024

    @Test
    fun theSixGigabytePhoneThatWasRefusedIsInTheSixClass() {
        // Honor CLK-LX2, sold as 6 GB: MemTotal 5,796,900 kB, measured 2026-10-24.
        assertTrue(RamGate.fits(memTotalKb(5_796_900), 6))
        // The other end of what a 6 GB phone reports.
        assertTrue(RamGate.fits(gib(5.3), 6))
        assertTrue(RamGate.fits(gib(5.9), 6))
    }

    @Test
    fun aBiggerPhoneStillFits() {
        // Pixel 10 Pro, sold as 16 GB: MemTotal 15,443,204 kB.
        assertTrue(RamGate.fits(memTotalKb(15_443_204), 6))
    }

    @Test
    fun aFourGigabytePhoneIsStillRefusedTheSixClass() {
        assertFalse(RamGate.fits(gib(3.5), 6))
        assertFalse(RamGate.fits(gib(3.8), 6))
        assertFalse(RamGate.fits(gib(4.0), 6))
        // The line itself: 85% of 6 GiB.
        assertFalse(RamGate.fits(gib(5.09), 6))
        assertTrue(RamGate.fits(gib(5.11), 6))
    }

    @Test
    fun theSameRuleHoldsForTheThreeClass() {
        assertTrue(RamGate.fits(gib(2.7), 3))
        assertTrue(RamGate.fits(gib(3.7), 3))
        assertFalse(RamGate.fits(gib(1.8), 3))
    }

    @Test
    fun aPhoneThatDoesNotSayIsNotRefused() {
        assertTrue(RamGate.fits(0L, 6))
    }
}
