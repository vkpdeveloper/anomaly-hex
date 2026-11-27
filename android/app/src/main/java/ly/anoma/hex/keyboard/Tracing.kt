package ly.anoma.hex.keyboard

import android.os.Trace

/** Names a stretch of keyboard work in a system trace (`atrace`), so a benchmark can read how long
 * each key took on the main thread and how many editor calls it made. Costs nothing unless a trace runs. */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
