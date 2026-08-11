package tech.gonxt.kate

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.gonxt.kate.audio.TtsChoice
import tech.gonxt.kate.audio.chooseTts
import tech.gonxt.kate.settings.KateVoice

class TtsRouterTest {

    @Test
    fun `kokoro when ready and battery fine`() {
        assertEquals(TtsChoice.KOKORO, chooseTts(KateVoice.EMMA, kokoroReady = true, piperReady = true, lowBattery = false))
    }

    @Test
    fun `piper on low battery`() {
        assertEquals(TtsChoice.PIPER, chooseTts(KateVoice.EMMA, kokoroReady = true, piperReady = true, lowBattery = true))
    }

    @Test
    fun `kokoro on low battery when piper missing — degrade, never fail`() {
        assertEquals(TtsChoice.KOKORO, chooseTts(KateVoice.EMMA, kokoroReady = true, piperReady = false, lowBattery = true))
    }

    @Test
    fun `explicit piper voice honoured`() {
        assertEquals(TtsChoice.PIPER, chooseTts(KateVoice.PIPER, kokoroReady = true, piperReady = true, lowBattery = false))
    }

    @Test
    fun `dummy when nothing downloaded`() {
        assertEquals(TtsChoice.DUMMY, chooseTts(KateVoice.EMMA, kokoroReady = false, piperReady = false, lowBattery = false))
    }

    @Test
    fun `piper when kokoro not downloaded`() {
        assertEquals(TtsChoice.PIPER, chooseTts(KateVoice.EMMA, kokoroReady = false, piperReady = true, lowBattery = false))
    }
}
