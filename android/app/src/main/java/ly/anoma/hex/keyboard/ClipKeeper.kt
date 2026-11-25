package ly.anoma.hex.keyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import ly.anoma.hex.R
import ly.anoma.hex.typing.TypingPreferences

internal enum class PhotoAccess { None, Chosen, All }

/** The keyboard's clipboard: it watches what is copied, keeps the history ([ClipHistory]) and the image
 * files clips hold, reads new screenshots when Keyboard › Recent screenshots allows, and puts images into the editor.
 * Files, the history and MediaStore are touched only on one worker thread, in order, so a file is never
 * pruned while a clip is being added; the views read the snapshots it posts to the main thread. */
internal class ClipKeeper(
    private val ime: HexKeyboard,
    /** The history, the photos or the chip's offer changed. */
    private val changed: () -> Unit,
    /** A short line in the toolbar, such as why an image was copied instead. */
    private val note: (String) -> Unit,
) {
    companion object {
        // Settings' Recent photos row writes these; the keyboard reads them.
        const val PREFS = "clipboard"
        const val RECENT_PHOTOS = "recentPhotos"
        const val ASKED_PHOTOS = "askedPhotos"
        /** The images the keyboard keeps: one folder in its private files, pinned clips' list beside them. */
        private const val DIR = "clipboard"
        private const val PINNED = "pinned.txt"
        /** Copies of photos made for an editor, in the cache, kept a day for an app that reads them late. */
        private const val PHOTOS = "photos"
        private const val FAVICONS = "favicons"
        private const val PHOTO_KEEP_MS = 24 * 60 * 60_000L
        /** A larger image is neither kept nor inserted. */
        private const val MAX_IMAGE_BYTES = 16L shl 20
        /** Thumbnails' longest side, in pixels: a card is about 100 dp tall. */
        private const val THUMB_PX = 256
        /** A burst of MediaStore changes (a screenshot writes, then renames) reads once. */
        private const val PHOTO_QUERY_DELAY_MS = 500L
        private val EXTENSION = Regex("[a-z0-9]{1,5}")
        /** How fresh a copy must be for links in it to be cleaned or expanded on the clipboard. */
        private const val REWRITE_WITHIN_MS = 5_000L

        /** Android 14 lets the person allow only photos they choose; before 13 it is storage. */
        fun photoPermissions() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        fun photoAccess(context: Context): PhotoAccess {
            fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            return when {
                granted(photoPermissions()[0]) -> PhotoAccess.All
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Chosen
                else -> PhotoAccess.None
            }
        }
        fun photosOn(context: Context) = photoAccess(context) != PhotoAccess.None &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RECENT_PHOTOS, false)
    }

    private val main = Handler(Looper.getMainLooper())
    // Work that arrives once the keyboard is destroyed is dropped, never a crash.
    private fun thread(name: String) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { Thread(it, name) }, ThreadPoolExecutor.DiscardPolicy())
    private val worker = thread("Hex clipboard")
    private val decoder = thread("Hex thumbnails")
    /** Asks shorteners where links go, off the clipboard's own thread. */
    private val links = thread("Hex short links")
    private val typing = TypingPreferences(ime)
    private val dir = File(ime.filesDir, DIR)
    private val photoDir = File(ime.cacheDir, PHOTOS)
    private val authority = ime.packageName + ".clips"
    private val clipboard = ime.getSystemService(ClipboardManager::class.java)
    private val history = ClipHistory() // Only touched on worker.
    private var written: String? = null // Only touched on worker.

    /** The history as last published: pinned first, newest first. */
    var clips: List<Clip> = emptyList()
        private set
    /** New screenshots, newest first, while Recent screenshots is on. */
    var photos: List<RecentImage> = emptyList()
        private set
    // The last copy, for the chip until it is used; [copyAt] is its clipboard timestamp, so the same
    // clip read again when the keyboard comes up is not offered again.
    private var copy: ChipOffer? = null
    private var copyAt = 0L
    private val usedPhotos = HashSet<Long>()
    /** Site icons for copied links, shown on the chip and in the panel. */
    val favicons = Favicons(File(ime.cacheDir, FAVICONS))
    /** Codes from texts, for the chip. */
    private val codes = TextedCodes(ime, changed)
    /** What [ClipPieces] found in each text clip the panel showed, by clip id; looked for once. */
    private val splits = HashMap<Long, ClipPiece.Split>()
    private val splitting = HashSet<Long>()
    private val thumbnails = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** What the toolbar's chip offers now: the last copy while fresh, or a newer photo or texted code. */
    val offer: ChipOffer?
        get() {
            val now = System.currentTimeMillis()
            val types = ime.currentInputEditorInfo?.contentMimeTypes.orEmpty()
            return ChipOffer.pick(copy, RecentImages.chip(photos, now, usedPhotos), now, codes.latest) { mime ->
                types.any { ClipDescription.compareMimeTypes(mime, it) }
            }
        }
    /** The chip was tapped, or typing passed over it: it offers that copy, screenshot or code no more;
     * the clipboard keeps a copy. */
    fun used(offer: ChipOffer) {
        when {
            offer is ChipOffer.Photo -> usedPhotos += offer.photo.id
            offer is ChipOffer.Code -> { if (offer == copy) copy = null; codes.used(offer) }
            else -> copy = null
        }
        changed()
    }

    /** What a text clip holds, for the panel: null until [ClipPieces] has looked, which this starts. */
    fun split(clip: Clip): ClipPiece.Split? {
        splits[clip.id]?.let { return it }
        val text = (clip.content as? ClipContent.Text)?.text ?: return null
        if (splitting.add(clip.id)) worker.execute {
            val split = ClipPieces.find(ime, text)
            main.post { splitting -= clip.id; splits[clip.id] = split; changed() }
        }
        return null
    }

    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { read() }
    private val expire = Runnable { worker.execute(::publish) }
    private var observing = false
    private val query = Runnable { if (ime.isInputViewShown) queryPhotos() }
    private val photosChanged = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(query); main.postDelayed(query, PHOTO_QUERY_DELAY_MS) }
    }

    /** The keyboard started: pinned clips are read back, and images no clip names any more go. Copies
     * are watched while the keyboard is hidden too, since they are made in other apps. */
    fun start() {
        clipboard.addPrimaryClipChangedListener(clipChanged)
        worker.execute {
            val saved = runCatching { File(dir, PINNED).takeIf { it.isFile }?.readText() }.getOrNull()
            if (saved != null) { history.read(saved) { File(dir, it).isFile }; written = saved }
            prunePhotos()
            publish()
        }
        syncPhotos()
        codes.sync()
    }
    /** The keyboard came up: a copy made while it was away, expiry, new photos and texted codes. */
    fun shown() { read(); worker.execute(::publish); syncPhotos(); codes.sync() }
    fun close() {
        clipboard.removePrimaryClipChangedListener(clipChanged)
        if (observing) ime.contentResolver.unregisterContentObserver(photosChanged)
        observing = false
        main.removeCallbacks(expire); main.removeCallbacks(query)
        worker.shutdown(); decoder.shutdown(); links.shutdown(); codes.close()
    }

    /** Reads the primary clip when it changed. It joins the history unless it is marked sensitive (a
     * password manager's copy) or was copied in a password field, and it is offered on the chip, masked
     * when sensitive. An image is copied into the keeper's own files first. */
    private fun read() {
        val primary = runCatching { clipboard.primaryClip }.getOrNull()
        if (primary == null) { if (copy != null) { copy = null; changed() }; return }
        val description = primary.description
        val at = description.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (at == copyAt) return
        copyAt = at
        copy = null
        // The extra predates Android 13's constant; password managers set it on older versions too.
        val sensitive = description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val keep = !sensitive && !(ime.engine.secure && ime.isInputViewShown)
        val item = primary.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val image = item?.uri?.takeIf { description.hasMimeType("image/*") }
        if (image != null) {
            // An image the keyboard itself copied (see [insert]) joins the history but isn't offered again.
            val offered = image.authority != authority
            val described = description.filterMimeTypes("image/*")?.firstOrNull()
            if (keep) worker.execute { keepImage(image, described, at, offered) }
        } else {
            // A URI that isn't an image (a file, a contact) would paste as its address.
            val text = (item?.text ?: if (item?.uri == null) item?.coerceToText(ime) else null)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                // Only a copy just made: one read again when the keyboard starts would come back as new.
                if (!sensitive && System.currentTimeMillis() - at < REWRITE_WITHIN_MS && rewrite(text, description.label, at)) return
                // A texted code copied from its message is offered as that code, and never kept.
                if (codes.isCode(text)) { copy = ChipOffer.Code(text.filter(Char::isDigit), at, -1); changed(); return }
                copy = ChipOffer.Text(text, sensitive, at)
                worker.execute {
                    if (history.add(ClipContent.Text(text), at, secret = !keep) != null) publish()
                    // A secret is only ever shown masked, so it isn't read for pieces.
                    if (sensitive) return@execute
                    val split = ClipPieces.find(ime, text)
                    main.post {
                        val offered = copy as? ChipOffer.Text ?: return@post
                        if (offered.at == at && offered.split == null) { copy = offered.copy(split = split); changed() }
                    }
                }
            }
        }
        changed()
    }

    /** With Remove tracking from links on, a copy whose links track is copied again without it; with
     * Expand short links on, a copied short link is copied again as the link it stands for, once the
     * shortener says (until then, and if it doesn't, the short link is kept as copied). True when the
     * copy will come back changed. */
    private fun rewrite(text: String, label: CharSequence?, at: Long): Boolean {
        val clean = typing.cleanLinks
        val cleaned = if (clean) Links.clean(text) else text
        if (cleaned != text) return copyAgain(label, cleaned)
        val short = Links.only(text)?.takeIf { typing.expandLinks && ShortLinks.isShort(it) } ?: return false
        copy = ChipOffer.Text(text, false, at)
        links.execute {
            val full = ShortLinks.expand(short)?.let { if (clean) Links.cleanLink(it) else it }
            main.post {
                when {
                    // Another copy came meanwhile: the history still gets this one, as it ended up.
                    copyAt != at -> worker.execute { if (history.add(ClipContent.Text(full ?: text), at) != null) publish() }
                    full == null || !copyAgain(label, full) ->
                        worker.execute { if (history.add(ClipContent.Text(text), at) != null) publish() }
                }
            }
        }
        changed()
        return true
    }
    private fun copyAgain(label: CharSequence?, text: String) =
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }.isSuccess

    private fun keepImage(uri: Uri, described: String?, at: Long, offered: Boolean) {
        val resolver = ime.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") && it != "image/*" }
            ?: described?.takeIf { it != "image/*" } ?: "image/png"
        val partial = File(dir, "image.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        dir.mkdirs()
        if (!copyImage(uri, partial, digest)) return
        val name = digest.digest().joinToString("") { "%02x".format(it) } + "." + extension(mime)
        val file = File(dir, name)
        if (file.exists()) partial.delete() else if (!partial.renameTo(file)) { partial.delete(); return }
        val clip = history.add(ClipContent.Image(name, mime), at) ?: return
        publish()
        if (offered) main.post { if (copyAt == at) { copy = ChipOffer.Image(clip); changed() } }
    }

    /** Copies at most [MAX_IMAGE_BYTES] from [uri] into [to]; false, with nothing left behind, when it can't. */
    private fun copyImage(uri: Uri, to: File, digest: MessageDigest? = null): Boolean {
        var size = 0L
        val copied = runCatching {
            ime.contentResolver.openInputStream(uri)?.use { input ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 shl 10)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        if (size > MAX_IMAGE_BYTES) return@use false
                        digest?.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    size > 0
                }
            } == true
        }.getOrDefault(false)
        if (!copied) to.delete()
        return copied
    }
    private fun extension(mime: String) =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()?.takeIf(EXTENSION::matches) ?: "img"

    /** On worker: expiry, the pinned list saved when it changed, unnamed images deleted, and the result
     * handed to the main thread with the next expiry scheduled. */
    private fun publish() {
        val list = history.list(System.currentTimeMillis())
        val pinned = history.write()
        if (pinned != written) {
            val file = File(dir, PINNED)
            if (pinned.isEmpty()) file.delete() else {
                dir.mkdirs()
                val partial = File(dir, "$PINNED.partial")
                runCatching { partial.writeText(pinned); partial.renameTo(file) }
            }
            written = pinned
        }
        val names = history.files()
        dir.listFiles()?.forEach { if (it.name != PINNED && it.name !in names) it.delete() }
        val next = history.nextExpiry()
        main.post {
            clips = list
            // An image clip that expired or was deleted no longer has a file to offer.
            (copy as? ChipOffer.Image)?.let { offered -> if (list.none { it.id == offered.clip.id }) copy = null }
            main.removeCallbacks(expire)
            if (next != null) main.postDelayed(expire, (next - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
            changed()
        }
    }

    fun pin(clip: Clip, pinned: Boolean, refused: () -> Unit) = worker.execute {
        if (!history.pin(clip.id, pinned, System.currentTimeMillis())) main.post(refused)
        publish()
    }
    fun delete(clip: Clip) = worker.execute { history.delete(clip.id); publish() }
    /** Clears every clip that isn't pinned. */
    fun clearRecent() = worker.execute { history.clearRecent(); publish() }

    /** Recent photos was turned on or off, or the keyboard came up and the permission may have changed. */
    fun syncPhotos() {
        val on = photosOn(ime)
        if (on != observing) {
            if (on) ime.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, photosChanged)
            else ime.contentResolver.unregisterContentObserver(photosChanged)
            observing = on
        }
        if (on) queryPhotos() else if (photos.isNotEmpty()) { photos = emptyList(); changed() }
    }
    /** The hour's newest images, of which [RecentImages.select] keeps screenshots, read on worker: a bounded query on DATE_ADDED. */
    private fun queryPhotos() = worker.execute {
        val now = System.currentTimeMillis()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(((now - RecentImages.PANEL_MS) / 1000).toString()))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, RecentImages.QUERY_LIMIT)
        }
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH)
        val rows = runCatching {
            ime.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(RecentImage(c.getLong(0), c.getLong(1) * 1000, c.getString(2).orEmpty(), c.getString(3).orEmpty()))
                }
            }
        }.getOrNull().orEmpty()
        val chosen = RecentImages.select(rows, now)
        main.post { if (photosOn(ime) && chosen != photos) { photos = chosen; changed() } }
    }
    private fun photoUri(photo: RecentImage) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)
    private fun prunePhotos() {
        val now = System.currentTimeMillis()
        photoDir.listFiles()?.forEach { if (now - it.lastModified() > PHOTO_KEEP_MS) it.delete() }
    }

    /** A thumbnail of a clip's image, decoded off the main thread; [show] runs on the main thread. */
    fun thumbnail(clip: Clip, show: (Bitmap?) -> Unit) {
        val image = clip.content as? ClipContent.Image ?: return
        thumbnail(image.file, show) { ThumbnailUtils.createImageThumbnail(File(dir, image.file), Size(THUMB_PX, THUMB_PX), null) }
    }
    fun thumbnail(photo: RecentImage, show: (Bitmap?) -> Unit) =
        thumbnail("photo:${photo.id}", show) { ime.contentResolver.loadThumbnail(photoUri(photo), Size(THUMB_PX, THUMB_PX), null) }
    private fun thumbnail(key: String, show: (Bitmap?) -> Unit, decode: () -> Bitmap) {
        thumbnails.get(key)?.let { show(it); return }
        decoder.execute {
            val bitmap = runCatching(decode).getOrNull()
            main.post { if (bitmap != null) thumbnails.put(key, bitmap); show(bitmap) }
        }
    }

    /** Puts a clip's image into the editor. */
    fun insert(clip: Clip) {
        val image = clip.content as? ClipContent.Image ?: return
        insert(File(dir, image.file), image.mime)
    }
    /** Puts a photo into the editor, from a copy in the cache: the editor can read only what Hex can grant. */
    fun insert(photo: RecentImage) = worker.execute {
        photoDir.mkdirs(); prunePhotos()
        val file = File(photoDir, "${photo.id}.${extension(photo.mime)}")
        val partial = File(photoDir, "photo.partial")
        val ready = file.isFile || (copyImage(photoUri(photo), partial) && partial.renameTo(file))
        main.post { if (ready) insert(file, photo.mime) }
    }
    /** Commits the image where the editor takes its type; elsewhere it goes on the clipboard, and the
     * toolbar says so. Either way the app reads it through Hex's FileProvider. */
    private fun insert(file: File, mime: String) {
        val ic = ime.currentInputConnection ?: return
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(ime, authority, file)
        val description = ClipDescription(ime.getString(R.string.keyboard_image), arrayOf(mime))
        val accepts = ime.currentInputEditorInfo?.contentMimeTypes.orEmpty().any { ClipDescription.compareMimeTypes(mime, it) }
        ime.engine.clearForDictation() // The word in progress stays as typed.
        if (accepts && ic.commitContent(InputContentInfo(uri, description), InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) return
        runCatching { clipboard.setPrimaryClip(ClipData(description, ClipData.Item(uri))) }
        note(ime.getString(R.string.keyboard_image_copied))
    }
}
