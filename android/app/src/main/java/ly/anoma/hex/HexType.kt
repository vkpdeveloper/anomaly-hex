package ly.anoma.hex

import android.graphics.Typeface
import android.graphics.fonts.FontFamily as NativeFontFamily
import android.graphics.fonts.Font as NativeFont
import android.widget.TextView
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Google Sans, the face Gboard draws its keys in, for the keyboard and the app alike. */
@OptIn(ExperimentalTextApi::class)
internal val sans = FontFamily(*listOf(400, 500, 600, 700).map { w ->
    Font(R.font.google_sans, weight = FontWeight(w),
        variationSettings = FontVariation.Settings(FontVariation.weight(w)))
}.toTypedArray())

private fun TextStyle.inSans() = copy(fontFamily = sans)
internal val hexTypography = Typography().run {
    copy(
        displayLarge = displayLarge.inSans(),
        displayMedium = displayMedium.inSans(),
        displaySmall = displaySmall.inSans(),
        headlineLarge = headlineLarge.inSans(),
        headlineMedium = headlineMedium.inSans(),
        headlineSmall = headlineSmall.inSans(),
        titleLarge = titleLarge.inSans(),
        titleMedium = titleMedium.inSans(),
        titleSmall = titleSmall.inSans(),
        bodyLarge = bodyLarge.inSans(),
        bodyMedium = bodyMedium.inSans(),
        bodySmall = bodySmall.inSans(),
        labelLarge = labelLarge.inSans(),
        labelMedium = labelMedium.inSans(),
        labelSmall = labelSmall.inSans(),
    )
}

// Android supplies missing script and emoji glyphs; Hex's own Latin labels use the same resource.
// One typeface per weight: fontVariationSettings on each label made a new native typeface every time the keys
// were drawn, and the keyboard's native memory grew with each field it opened in.
private val sansWeights = HashMap<Int, Typeface>()
// The 5 MB font file is read once and every weight shares it: each read from the resource took most of the
// keyboard's first appearance and kept another copy in memory.
private var sansFile: NativeFont? = null
internal fun TextView.useSans(weight: Int = 400) {
    typeface = sansWeights.getOrPut(weight) {
        val file = sansFile ?: NativeFont.Builder(resources, R.font.google_sans).build().also { sansFile = it }
        val font = NativeFont.Builder(file).setWeight(weight).setFontVariationSettings("'wght' $weight").build()
        Typeface.CustomFallbackBuilder(NativeFontFamily.Builder(font).build()).setSystemFallback("sans-serif").build()
    }
}
