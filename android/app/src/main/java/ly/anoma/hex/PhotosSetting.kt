package ly.anoma.hex

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import ly.anoma.hex.keyboard.ClipKeeper
import ly.anoma.hex.keyboard.PhotoAccess

/** Keyboard › Recent photos: on while it was turned on and Android lets Hex read photos. [partial] is
 * Android 14's "selected photos" grant, which leaves out the screenshots taken after it. */
internal class RecentPhotos(val on: Boolean, val partial: Boolean, val change: (Boolean) -> Unit)

/** The row's state, read again whenever Hex comes back from the permission dialog or Android's settings. */
@Composable internal fun rememberRecentPhotos(context: Context): RecentPhotos {
    val prefs = remember { context.getSharedPreferences(ClipKeeper.PREFS, Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) prefs.edit().putBoolean(ClipKeeper.RECENT_PHOTOS, true).apply()
        revision++
    }
    val access = remember(revision) { ClipKeeper.photoAccess(context) }
    val wanted = remember(revision) { prefs.getBoolean(ClipKeeper.RECENT_PHOTOS, false) }
    return RecentPhotos(wanted && access != PhotoAccess.None, access == PhotoAccess.Chosen) { on ->
        val permissions = ClipKeeper.photoPermissions()
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val asked = prefs.getBoolean(ClipKeeper.ASKED_PHOTOS, false)
        prefs.edit().putBoolean(ClipKeeper.RECENT_PHOTOS, on).putBoolean(ClipKeeper.ASKED_PHOTOS, asked || (on && access == PhotoAccess.None)).apply()
        when {
            !on || access == PhotoAccess.All -> {}
            // Android asks no more once refused twice; its settings page is the way left.
            access == PhotoAccess.None && asked && activity?.shouldShowRequestPermissionRationale(permissions[0]) == false ->
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            // With only chosen photos, asking again lets the person allow all of them.
            else -> ask.launch(permissions)
        }
        revision++
    }
}
