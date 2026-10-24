package app.maskan.chat.ondevice

/**
 * Whether a phone is in a model's RAM class (2.7 plan item 15).
 *
 * Android's `totalMem` leaves out what the kernel and the hardware reserve, so a phone reports less
 * than the number on its box: the Honor CLK-LX2, sold as 6 GB, reports 5.53 GiB (92%); the Pixel 10
 * Pro, sold as 16 GB, 14.7 GiB (92%). Compared with a 6 GiB constant, every 6 GB phone was refused
 * and told it needed 6 GB.
 *
 * So a phone is in a class when it reports at least [REPORTED_SHARE] of it. Phones report 90-95%;
 * the class below is two thirds of this one or less (4 of 6, 2 of 3), so a smaller phone cannot pass
 * by rounding. Pure, so the tests reach it without a phone.
 */
internal object RamGate {

    const val GIB = 1024L * 1024 * 1024

    /** Of its class, the least a phone may report and still be in it. Humam, 2026-10-24. */
    const val REPORTED_SHARE = 0.85

    /**
     * True when [reportedBytes] is in the [classGb] class, or when the phone did not say (0): a
     * number nobody has refuses nobody, and the load itself fails honestly if the model cannot fit.
     */
    fun fits(reportedBytes: Long, classGb: Int): Boolean =
        reportedBytes == 0L || reportedBytes >= (classGb * GIB * REPORTED_SHARE).toLong()
}
