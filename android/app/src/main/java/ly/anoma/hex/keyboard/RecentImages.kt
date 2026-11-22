package ly.anoma.hex.keyboard

/** A picture in MediaStore: [addedAt] is when it was added, in wall-clock milliseconds, and [path] its
 * RELATIVE_PATH, such as `DCIM/Camera/` or `Pictures/Screenshots/`. */
internal data class RecentImage(val id: Long, val addedAt: Long, val mime: String, val path: String)

/** Which new screenshots the keyboard offers, as Gboard offers a screenshot just taken. */
internal object RecentImages {
    /** The toolbar's chip offers the newest one this fresh, until it is used. */
    const val CHIP_MS = 5 * 60_000L
    /** The clipboard panel shows those from the last hour, as copies stay there an hour. */
    const val PANEL_MS = ClipHistory.KEEP_MS
    const val PANEL_MAX = 8
    /** The most rows one query reads: the hour's newest images, before [screenshot] filters them. */
    const val QUERY_LIMIT = 40
    /** A clock that went back a little doesn't hide a photo; one far in the future is not believed. */
    private const val SKEW_MS = 60_000L

    /** Screenshots only: never camera photos, nor what other apps saved, such as a chat's downloads. */
    fun screenshot(path: String) = path.contains("Screenshots/", ignoreCase = true)

    /** The panel's screenshots, newest first: those made in the last [PANEL_MS]. */
    fun select(rows: List<RecentImage>, now: Long): List<RecentImage> = rows
        .filter { it.mime.startsWith("image/") && screenshot(it.path) && now - it.addedAt in -SKEW_MS until PANEL_MS }
        .distinctBy { it.id }.sortedByDescending { it.addedAt }.take(PANEL_MAX)

    /** The photo the toolbar offers: the newest of [photos] while it is fresh and hasn't been [used]. */
    fun chip(photos: List<RecentImage>, now: Long, used: Set<Long>): RecentImage? =
        photos.maxByOrNull { it.addedAt }?.takeIf { now - it.addedAt in -SKEW_MS until CHIP_MS && it.id !in used }
}

/** What the toolbar's chip offers: the last copy (text or an image) or a new photo, whichever is newer. */
internal sealed interface ChipOffer {
    val at: Long
    /** [sensitive] text, such as a password a password manager copied, shows masked. */
    data class Text(val text: String, val sensitive: Boolean, override val at: Long) : ChipOffer
    data class Image(val clip: Clip) : ChipOffer { override val at get() = clip.at }
    data class Photo(val photo: RecentImage) : ChipOffer { override val at get() = photo.addedAt }

    /** How long until this offer is too old for the chip. */
    fun staleIn(now: Long) = (if (this is Photo) RecentImages.CHIP_MS else COPY_FRESH_MS) - (now - at)

    companion object {
        /** A copy older than this no longer gets the chip. */
        const val COPY_FRESH_MS = 10 * 60_000L
        /** [copy] while it is fresh, or [photo] (already chosen by [RecentImages.chip]) when it is newer. */
        fun pick(copy: ChipOffer?, photo: RecentImage?, now: Long): ChipOffer? =
            listOfNotNull(copy?.takeIf { now - it.at < COPY_FRESH_MS }, photo?.let(::Photo)).maxByOrNull { it.at }
    }
}
