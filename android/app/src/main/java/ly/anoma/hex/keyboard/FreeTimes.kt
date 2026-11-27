package ly.anoma.hex.keyboard

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** "I'm free " offers the free times in your calendar as chips (Settings › Keyboard › Free times, off by
 * default, and only with the Calendar permission). A time is free between 9 am and 9 pm, for at least
 * half an hour, with nothing busy in any calendar. "free tomorrow ", "free on friday " and "available "
 * ask too; without a day, today's times are offered, else tomorrow's. */
internal object FreeTimes {
    const val PERMISSION = Manifest.permission.READ_CALENDAR
    private val DAY_START: LocalTime = LocalTime.of(9, 0)
    private val DAY_END: LocalTime = LocalTime.of(21, 0)
    private const val SHORTEST = 30L
    private const val CHIPS = 3

    data class Busy(val start: LocalDateTime, val end: LocalDateTime)

    fun allowed(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private val ASKED = Regex("""\b(?:free|available)(?:\s+(?:on\s+|this\s+)?(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday))? $""",
        RegexOption.IGNORE_CASE)

    /** Chips for [before] if it just asked; [busy] reads the calendar for a day, null when it can't. */
    fun offer(before: String, now: LocalDateTime, h24: Boolean, busy: (LocalDate) -> List<Busy>?): List<StripAction.Insert> {
        val match = ASKED.find(before) ?: return emptyList()
        val named = match.groupValues[1].lowercase(Locale.ROOT)
        val today = now.toLocalDate()
        val days = when (named) {
            "" -> listOf(today, today.plusDays(1))
            "today" -> listOf(today)
            "tomorrow" -> listOf(today.plusDays(1))
            else -> listOf(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(named.uppercase(Locale.ROOT)))))
        }
        for (day in days) {
            val windows = windows(busy(day) ?: return emptyList(), day, now)
            if (windows.isEmpty()) continue
            // Without a day the chip says which: "today 3 to 5 pm".
            val prefix = if (named.isNotEmpty()) "" else if (day == today) "today " else "tomorrow "
            return windows.take(CHIPS).map { (start, end) ->
                val text = prefix + say(start, end, h24)
                StripAction.Insert(text, 0, text)
            }
        }
        return emptyList()
    }

    /** The free stretches of [day] between 9 am and 9 pm, after [now], each at least half an hour, on the
     * half hour. */
    fun windows(busy: List<Busy>, day: LocalDate, now: LocalDateTime): List<Pair<LocalTime, LocalTime>> {
        var from = day.atTime(DAY_START)
        if (now > from) from = halfHourUp(now)
        val end = day.atTime(DAY_END)
        val out = ArrayList<Pair<LocalTime, LocalTime>>()
        for (b in busy.filter { it.end > from && it.start < end }.sortedBy { it.start } + Busy(end, end)) {
            val gapEnd = halfHourDown(minOf(b.start, end))
            if (java.time.Duration.between(from, gapEnd).toMinutes() >= SHORTEST) out += from.toLocalTime() to gapEnd.toLocalTime()
            if (b.end > from) from = halfHourUp(b.end)
            if (from >= end) break
        }
        return out
    }

    /** "3 to 5 pm", "10 am to 1 pm", "9:30 to 11 am"; or "15:00 to 17:00" on a 24-hour phone. */
    fun say(start: LocalTime, end: LocalTime, h24: Boolean): String {
        if (h24) return "%d:%02d to %d:%02d".format(start.hour, start.minute, end.hour, end.minute)
        fun clock(t: LocalTime) = (if (t.hour % 12 == 0) 12 else t.hour % 12).toString() + if (t.minute != 0) ":%02d".format(t.minute) else ""
        fun half(t: LocalTime) = if (t.hour < 12) "am" else "pm"
        return if (half(start) == half(end)) "${clock(start)} to ${clock(end)} ${half(end)}"
            else "${clock(start)} ${half(start)} to ${clock(end)} ${half(end)}"
    }

    private fun halfHourUp(t: LocalDateTime): LocalDateTime {
        val down = halfHourDown(t)
        return if (down == t) t else down.plusMinutes(30)
    }
    private fun halfHourDown(t: LocalDateTime): LocalDateTime =
        t.withSecond(0).withNano(0).withMinute(if (t.minute >= 30) 30 else 0)

    /** What makes [day] busy in every calendar on the phone: events marked busy, not all-day or declined. */
    fun busy(resolver: ContentResolver, day: LocalDate): List<Busy>? {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        val where = "${CalendarContract.Instances.ALL_DAY} = 0 AND ${CalendarContract.Instances.AVAILABILITY} = " +
            "${CalendarContract.Events.AVAILABILITY_BUSY} AND ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != " +
            "${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        return runCatching {
            resolver.query(uri, columns, where, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(Busy(
                        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(0)), zone),
                        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(1)), zone)))
                }
            }
        }.getOrNull()
    }
}
