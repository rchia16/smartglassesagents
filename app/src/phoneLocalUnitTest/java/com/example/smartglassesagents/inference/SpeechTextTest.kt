package com.example.smartglassesagents.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun stripsMarkdownAndLimitsLength() {
        val speech = ("**Result:** " + "visible detail ".repeat(40)).toSpeechText()

        assertTrue(speech.length <= 240)
        assertFalse(speech.contains('*'))
        assertTrue(speech.endsWith('.'))
    }

    @Test
    fun preservesShortPlainAnswer() {
        val answer = "I see two faces, one in the center and one on the right."

        assertTrue(answer.toSpeechText() == answer)
    }
}
