package tech.gonxt.kate.sync

import kotlin.math.max

/**
 * Hybrid logical clock (spec §2.2). Encoded sortable as
 * `<physical-ms 14 digits>-<counter 4 hex>-<device>` so plain string
 * comparison orders events across devices.
 */
class Hlc(private val deviceId: String, private val wallClock: () -> Long = System::currentTimeMillis) {

    private var lastPhysical = 0L
    private var counter = 0

    @Synchronized
    fun tick(): String {
        val now = wallClock()
        if (now > lastPhysical) {
            lastPhysical = now
            counter = 0
        } else {
            counter++
        }
        return encode(lastPhysical, counter, deviceId)
    }

    /** Fold in a remote timestamp so local time never runs behind it. */
    @Synchronized
    fun update(remote: String) {
        val remotePhysical = remote.substringBefore('-').toLongOrNull() ?: return
        val remoteCounter = remote.split('-').getOrNull(1)?.toIntOrNull(16) ?: 0
        val now = wallClock()
        val newPhysical = max(max(lastPhysical, remotePhysical), now)
        counter = when (newPhysical) {
            lastPhysical, remotePhysical ->
                max(if (newPhysical == lastPhysical) counter else -1, if (newPhysical == remotePhysical) remoteCounter else -1) + 1
            else -> 0
        }
        lastPhysical = newPhysical
    }

    companion object {
        fun encode(physicalMs: Long, counter: Int, device: String): String =
            physicalMs.toString().padStart(14, '0') + "-" +
                counter.toString(16).padStart(4, '0') + "-" + device
    }
}
