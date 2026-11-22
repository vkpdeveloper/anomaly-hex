package ly.anoma.hex.keyboard

import android.content.Context
import android.graphics.drawable.Icon
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import ly.anoma.hex.R
import ly.anoma.hex.typing.Suggestion
import kotlin.math.roundToInt

/** What the phone's autofill service offers for the field (saved passwords and usernames, a code from a
 * text message), as chips in the strip, as Gboard shows them: Android's inline suggestions. The service
 * draws each chip in the style asked for here and fills the field when one is tapped; it reads any text
 * message itself, so Hex never sees one and asks for no SMS permission. */
internal class InlineAutofill(private val context: Context, show: (unpinned: List<View>, pinned: List<View>) -> Unit) {
    private val chips = InlineChips(show)
    private val density = context.resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private val height = px(KeyboardMetrics.PILL)

    /** Chips as tall as the strip's clipboard chip and as wide as the strip between the grid and the
     * mic, in the keyboard's colours for the current light or dark theme (asked again for each field). */
    fun request(): InlineSuggestionsRequest {
        val widest = (context.resources.displayMetrics.widthPixels - px(STRIP_ENDS)).coerceAtLeast(px(MIN_WIDTH))
        val spec = InlinePresentationSpec.Builder(Size(px(MIN_WIDTH), height), Size(widest, height))
            .setStyle(style()).build()
        // One spec serves every chip: Android repeats the last spec for the rest.
        return InlineSuggestionsRequest.Builder(listOf(spec)).build()
    }

    private fun style(): android.os.Bundle {
        val background = Icon.createWithResource(context, R.drawable.autofill_chip).setTint(context.getColor(R.color.kb_key))
        val icon = ImageViewStyle.Builder().setMaxWidth(px(ICON)).setMaxHeight(px(ICON))
            .setLayoutMargin(0, 0, px(8f), 0).build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(ViewStyle.Builder().setBackground(background).setPadding(px(12f), 0, px(14f), 0).build())
            .setTitleStyle(TextViewStyle.Builder().setTextColor(context.getColor(R.color.kb_ink))
                .setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(context.getColor(R.color.kb_quiet))
                .setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f).setLayoutMargin(px(6f), 0, 0, 0).build())
            .setStartIconStyle(icon)
            .setEndIconStyle(ImageViewStyle.Builder().setMaxWidth(px(ICON)).setMaxHeight(px(ICON))
                .setLayoutMargin(px(8f), 0, 0, 0).build())
            // A chip that is only an icon (the service's own entry, such as its key for saved passwords).
            .setSingleIconChipStyle(ViewStyle.Builder().setBackground(background).setPadding(px(9f), 0, px(9f), 0).build())
            .setSingleIconChipIconStyle(ImageViewStyle.Builder().setMaxWidth(px(ICON)).setMaxHeight(px(ICON)).build())
            .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }

    /** A response for the field: its chips once they have all inflated; an empty one clears them. */
    fun offer(suggestions: List<InlineSuggestion>) {
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, height)
        chips.offer(suggestions.map { it.info.isPinned }) { i, done ->
            try {
                suggestions[i].inflate(context, size, context.mainExecutor) { done(it) }
            } catch (_: Exception) {
                // A size or style the service turns down: that chip is left out, the rest still show.
                done(null)
            }
        }
    }

    fun clear() = chips.clear()

    private companion object {
        const val MIN_WIDTH = 40f
        const val ICON = 20f
        /** The grid button's and the mic's room at the strip's ends. */
        const val STRIP_ENDS = 98f
    }
}

/** A word being typed, or the caret in one, takes the strip back from the chips for its own suggestions,
 * as Gboard's does. The chips are kept, not dropped: the service may send no new response, so they show
 * again once the word is done or deleted. Next-word guesses and an empty strip leave them showing, as do
 * password and number fields, which get no word suggestions. */
internal fun wordInProgress(words: List<Suggestion>) =
    words.any { it.kind != Suggestion.Kind.Next && it.kind != Suggestion.Kind.Emoji }

/** One response's chips, which inflate one by one in any order: shown together in the response's order
 * once all are back, pinned ones after the rest (Gboard keeps them at the strip's end, by the mic).
 * A chip that failed is left out, and a newer response or a clear drops what an older one still has on
 * the way, so a late chip never lands in another field's strip. */
internal class InlineChips<V : Any>(private val show: (unpinned: List<V>, pinned: List<V>) -> Unit) {
    private var generation = 0

    fun offer(pinned: List<Boolean>, inflate: (index: Int, done: (V?) -> Unit) -> Unit) {
        val id = ++generation
        if (pinned.isEmpty()) { show(emptyList(), emptyList()); return }
        val inflated = MutableList<V?>(pinned.size) { null }
        var waiting = pinned.size
        for (i in pinned.indices) inflate(i) { chip ->
            if (id != generation) return@inflate
            inflated[i] = chip
            if (--waiting == 0) {
                val (last, first) = pinned.indices.partition { pinned[it] }
                show(first.mapNotNull { inflated[it] }, last.mapNotNull { inflated[it] })
            }
        }
    }

    fun clear() { generation++; show(emptyList(), emptyList()) }
}
