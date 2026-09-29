package com.nexg.ide.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contrast verification for the palette in Color.kt.
 *
 * This test exists because Color.kt documents measured contrast ratios. An
 * unverified accessibility claim is worse than no claim, so the numbers in the
 * file are checked here and will fail loudly if a colour is changed without
 * re-measuring.
 *
 * Thresholds follow WCAG 2.x:
 *   4.5:1  AA, normal text
 *   3.0:1  AA, large text and non-text UI components
 *   7.0:1  AAA, normal text
 */
class ColorContrastTest {

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = max(a, b)
        val darker = min(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    // ---- dark scheme -------------------------------------------------------

    @Test
    fun `body text meets AA on both dark surfaces`() {
        assertThat(contrastRatio(NexGText, NexGBlack)).isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGText, NexGDeepSpace)).isAtLeast(AA_NORMAL)
    }

    @Test
    fun `secondary text meets AA on both dark surfaces`() {
        assertThat(contrastRatio(NexGTextSecondary, NexGBlack)).isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGTextSecondary, NexGDeepSpace)).isAtLeast(AA_NORMAL)
    }

    @Test
    fun `primary text on primary container meets AA`() {
        // This is the pairing a filled button uses: dark text on violet.
        assertThat(contrastRatio(NexGBlack, NexGViolet)).isAtLeast(AA_NORMAL)
    }

    @Test
    fun `status accents meet AA for non-text use on black`() {
        // Status colours back icons and chip borders, so the 3:1 non-text
        // threshold applies, not the 4.5:1 text threshold.
        assertThat(contrastRatio(NexGCyan, NexGBlack)).isAtLeast(AA_LARGE)
        assertThat(contrastRatio(NexGMint, NexGBlack)).isAtLeast(AA_LARGE)
        assertThat(contrastRatio(NexGAmber, NexGBlack)).isAtLeast(AA_LARGE)
        assertThat(contrastRatio(NexGRed, NexGBlack)).isAtLeast(AA_LARGE)
    }

    @Test
    fun `violet is usable as a large-text accent on black`() {
        // Documented in Color.kt as having little headroom. Asserted at the
        // large-text threshold, and the test is what justifies that note.
        assertThat(contrastRatio(NexGViolet, NexGBlack)).isAtLeast(AA_LARGE)
    }

    // ---- light scheme ------------------------------------------------------

    @Test
    fun `body text meets AA on light surfaces`() {
        assertThat(contrastRatio(NexGLightText, NexGLightSurface)).isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGLightText, NexGLightSurfaceElevated)).isAtLeast(AA_NORMAL)
    }

    @Test
    fun `light scheme accents meet AA against their surfaces`() {
        // The same violet and cyan that pass on black fail on white, which is
        // exactly why the light scheme uses darkened variants.
        val lightPrimary = NexGLightColors.primary
        val lightSecondary = NexGLightColors.secondary
        val lightTertiary = NexGLightColors.tertiary
        val lightError = NexGLightColors.error

        assertThat(contrastRatio(lightPrimary, NexGLightSurfaceElevated))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(lightSecondary, NexGLightSurfaceElevated))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(lightTertiary, NexGLightSurfaceElevated))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(lightError, NexGLightSurfaceElevated))
            .isAtLeast(AA_NORMAL)
    }

    @Test
    fun `onPrimary pairing is legible in both schemes`() {
        assertThat(contrastRatio(NexGDarkColors.onPrimary, NexGDarkColors.primary))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGLightColors.onPrimary, NexGLightColors.primary))
            .isAtLeast(AA_NORMAL)
    }

    @Test
    fun `onSurface pairing is legible in both schemes`() {
        assertThat(contrastRatio(NexGDarkColors.onSurface, NexGDarkColors.surface))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGLightColors.onSurface, NexGLightColors.surface))
            .isAtLeast(AA_NORMAL)
    }

    @Test
    fun `onSurfaceVariant pairing is legible in both schemes`() {
        assertThat(contrastRatio(NexGDarkColors.onSurfaceVariant, NexGDarkColors.surface))
            .isAtLeast(AA_NORMAL)
        assertThat(contrastRatio(NexGLightColors.onSurfaceVariant, NexGLightColors.surface))
            .isAtLeast(AA_NORMAL)
    }

    // ---- palette integrity -------------------------------------------------

    @Test
    fun `documented ratios in the palette are accurate`() {
        // Guards the comment block in Color.kt. If a colour is edited and this
        // fails, the documented number must be re-measured, not deleted.
        //
        // These values were measured with the WCAG 2.x formula and replaced
        // the hand-estimated ones an earlier draft carried.
        assertThat(round2(contrastRatio(NexGText, NexGBlack))).isEqualTo(17.12)
        assertThat(round2(contrastRatio(NexGText, NexGDeepSpace))).isEqualTo(15.95)
        assertThat(round2(contrastRatio(NexGTextSecondary, NexGBlack))).isEqualTo(7.99)
        assertThat(round2(contrastRatio(NexGTextSecondary, NexGDeepSpace))).isEqualTo(7.44)
        assertThat(round2(contrastRatio(NexGViolet, NexGBlack))).isEqualTo(4.66)
        assertThat(round2(contrastRatio(NexGCyan, NexGBlack))).isEqualTo(13.17)
        assertThat(round2(contrastRatio(NexGMint, NexGBlack))).isEqualTo(15.17)
        assertThat(round2(contrastRatio(NexGAmber, NexGBlack))).isEqualTo(11.07)
        assertThat(round2(contrastRatio(NexGRed, NexGBlack))).isEqualTo(6.3)
        assertThat(round2(contrastRatio(NexGLightText, NexGLightSurface))).isEqualTo(18.29)
        assertThat(round2(contrastRatio(NexGBlack, NexGViolet))).isEqualTo(4.66)
    }

    @Test
    fun `violet keeps headroom above the AA threshold`() {
        // NexGViolet is the tightest pairing in the palette. This asserts a
        // margin rather than the bare threshold, so a small edit to the violet
        // cannot quietly drop body text below AA.
        assertThat(contrastRatio(NexGViolet, NexGBlack)).isAtLeast(4.6)
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private companion object {
        const val AA_NORMAL = 4.5
        const val AA_LARGE = 3.0
    }
}
