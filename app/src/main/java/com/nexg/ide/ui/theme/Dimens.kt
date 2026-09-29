package com.nexg.ide.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing scale (PLAN.MD 6.3: 4/8/12/16/24).
 *
 * Every gap in the app should come from here. Ad-hoc `16.dp` literals
 * scattered through screens are how a layout ends up with three unrelated
 * "medium" gaps that nobody can change together.
 */
object Dimens {
    val spaceXs: Dp = 4.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 12.dp
    val spaceLg: Dp = 16.dp
    val spaceXl: Dp = 24.dp

    /**
     * Minimum interactive size (PLAN.MD 6.4, accessibility).
     *
     * This is a floor, not a target. A visible icon at 24.dp still gets a
     * 48.dp touch area — the padding is applied to the parent, so the icon can
     * look small while the tappable region stays accessible.
     */
    val minTouchTarget: Dp = 48.dp

    val cardCorner: Dp = 16.dp
    val chipCorner: Dp = 8.dp

    /** Height of the bottom navigation bar. >= 48.dp by construction. */
    val bottomBarHeight: Dp = 64.dp

    /** Height of the top app bar. Also >= 48.dp so it can host a tap target. */
    val topBarHeight: Dp = 56.dp
}
