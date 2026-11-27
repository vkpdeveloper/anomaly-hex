package ly.anoma.hex.relay

import ly.anoma.hex.speech.Spoken
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a transcription says about where it was spoken: the computer picks the app's mode from it. */
class OriginTest {
    @Test fun theQueryNamesTheAppItsPackageAndTheLayoutsLanguage() {
        val origin = HexClient.Origin("WhatsApp Business", "com.whatsapp.w4b", HexClient.Origin.language(Spoken.Hinglish))
        assertEquals("?app=WhatsApp+Business&package=com.whatsapp.w4b&language=hi", origin.query())
        assertEquals("?app=Caf%C3%A9+%26+Bar", HexClient.Origin("Café & Bar").query())
    }

    @Test fun nothingKnownSendsNoQuery() {
        assertEquals("", HexClient.Origin().query())
        assertEquals("?language=no", HexClient.Origin(" ", null, HexClient.Origin.language(Spoken.Norwegian)).query())
    }
}
