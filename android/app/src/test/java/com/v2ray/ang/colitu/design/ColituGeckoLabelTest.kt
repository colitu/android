package com.v2ray.ang.colitu.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ColituGecko label's colours keep text contrast (WCAG AA, 4.5:1) on the surfaces it is drawn on. */
class ColituGeckoLabelTest {
    @Test
    fun darkPalette_readableOnTheSettingsTileAndTheChip() {
        val (deep, light, _) = ColituGeckoPalette.colors(dark = true)
        val chip = ColituGeckoPalette.over(ColituColors.glass10, ColituColors.bg)
        for (surface in listOf(ColituColors.surface, ColituColors.bg, chip)) {
            assertTrue(ColituGeckoPalette.contrast(deep, surface) >= 4.5)
            assertTrue(ColituGeckoPalette.contrast(light, surface) >= 4.5)
        }
    }

    @Test
    fun lightPalette_readableOnTheBrandBackground() {
        val (deep, light, _) = ColituGeckoPalette.colors(dark = false)
        val brandBackground = Color(0xFFF4F7F7)
        assertTrue(ColituGeckoPalette.contrast(deep, brandBackground) >= 4.5)
        assertTrue(ColituGeckoPalette.contrast(light, brandBackground) >= 4.5)
        assertEquals(Color(0xFF262280), deep)
        assertEquals(Color(0xFF6650CC), light)
    }

    @Test
    fun timings_asOnWindows() {
        assertEquals(3_500, ColituGeckoPalette.SLIDE_MS)
        assertEquals(2_000, ColituGeckoPalette.BREATHE_HALF_MS * 2)
        assertEquals(21.0, ColituGeckoPalette.contrast(Color.Black, Color.White), 0.01)
    }
}
