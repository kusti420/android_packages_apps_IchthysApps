/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ichthys.apps.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.internal.R as IR

/**
 * Ichthys Apps' slice of the IchthysOS "Shoal" look, derived only from the shared framework tokens
 * (frameworks/base core/res values/ichthys_tokens.xml, written by the theming service from
 * ichthys.nix): the ichthys_* colours and dimens, the three ichthys_font_* families and
 * ichthys_surface_style. Nothing here is a hex value. Same rules as Settings' Guard
 * (GuardTheme.kt):
 * - the window is the ink ground ([ink]); groups are cards, outlined by a [hairline] when the
 *   surface style is hairline and filled without a stroke when it is tonal;
 * - [accent] marks at most one active element per group (the selected tab, the one primary action,
 *   a progress bar). Material's primary role is mapped to the text colour, so checkboxes, switches
 *   and buttons stay monochrome unless a screen asks for the accent;
 * - everything else is [text] or [muted] text on hairlines.
 */
@Immutable
data class AppsTokens(
    val ink: Color,
    val card: Color,
    val raised: Color,
    val hairline: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentTint: Color,
    val onAccent: Color,
    val track: Color,
    val danger: Color,
    val mono: FontFamily,
    val display: FontFamily,
    val body: FontFamily,
    val radius: Dp,
    val radiusSmall: Dp,
    val hairlineWidth: Dp,
    /** ichthys_surface_style: hairline (outlined cards) unless it is tonal (2). */
    val hairlineSurfaces: Boolean,
)

val LocalAppsTokens = compositionLocalOf<AppsTokens> { error("AppsTheme missing") }

object Shoal {
    val tokens: AppsTokens
        @Composable get() = LocalAppsTokens.current
}

private fun Context.dimenDp(id: Int): Dp =
    (resources.getDimension(id) / resources.displayMetrics.density).dp

/** A theme.font.* family (named in fonts_customization.xml) at [weight]. */
private fun Context.family(id: Int, fallback: String, weight: Int): FontFamily {
    val name =
        try {
            resources.getString(id).ifEmpty { fallback }
        } catch (e: Resources.NotFoundException) {
            fallback
        }
    return FontFamily(Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false))
}

private fun isNight(context: Context) =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** Reads the tokens for [context]'s configuration (the unsuffixed ids follow night mode). */
fun appsTokens(context: Context): AppsTokens {
    fun c(id: Int) = Color(context.getColor(id))
    val surfaceStyle =
        try {
            context.resources.getInteger(IR.integer.ichthys_surface_style)
        } catch (e: Resources.NotFoundException) {
            1
        }
    return AppsTokens(
        ink = c(IR.color.ichthys_ink),
        card = c(IR.color.ichthys_card),
        raised = c(IR.color.ichthys_raised),
        hairline = c(IR.color.ichthys_hairline),
        outline = c(IR.color.ichthys_outline),
        text = c(IR.color.ichthys_text),
        muted = c(IR.color.ichthys_muted),
        accent = c(IR.color.ichthys_accent),
        accentTint = c(IR.color.ichthys_accent_tint),
        onAccent = c(IR.color.ichthys_on_accent),
        track = c(IR.color.ichthys_track),
        danger =
            c(
                if (isNight(context)) android.R.color.system_error_dark
                else android.R.color.system_error_light
            ),
        mono = context.family(IR.string.ichthys_font_mono, "monospace", 400),
        display = context.family(IR.string.ichthys_font_display, "sans-serif", 600),
        body = context.family(IR.string.ichthys_font_text, "sans-serif", 400),
        radius = context.dimenDp(IR.dimen.ichthys_radius),
        radiusSmall = context.dimenDp(IR.dimen.ichthys_radius_small),
        hairlineWidth = context.dimenDp(IR.dimen.ichthys_hairline_width),
        hairlineSurfaces = surfaceStyle != 2,
    )
}

private fun schemeFor(t: AppsTokens, night: Boolean): ColorScheme {
    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        // Monochrome primary: the accent is applied explicitly, once per group.
        primary = t.text,
        onPrimary = t.ink,
        primaryContainer = t.raised,
        onPrimaryContainer = t.text,
        secondary = t.muted,
        onSecondary = t.ink,
        secondaryContainer = t.raised,
        onSecondaryContainer = t.text,
        tertiary = t.muted,
        onTertiary = t.ink,
        background = t.ink,
        onBackground = t.text,
        surface = t.ink,
        onSurface = t.text,
        surfaceVariant = t.raised,
        onSurfaceVariant = t.muted,
        surfaceTint = t.ink,
        surfaceBright = t.raised,
        surfaceDim = t.ink,
        surfaceContainerLowest = t.ink,
        surfaceContainerLow = t.card,
        surfaceContainer = t.card,
        surfaceContainerHigh = t.raised,
        surfaceContainerHighest = t.raised,
        outline = t.outline,
        outlineVariant = t.hairline,
        // IchthysOS: the snackbar (config write errors) is drawn in inverseSurface; left at the
        // M3 baseline it was a fixed grey that ignores the palette in both themes.
        inverseSurface = t.text,
        inverseOnSurface = t.ink,
        error = t.danger,
    )
}

@Composable
fun AppsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    val night =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val tokens = remember(ctx, config.uiMode) { appsTokens(ctx) }
    val scheme = remember(tokens, night) { schemeFor(tokens, night) }
    val typography =
        remember(tokens) {
            val t = Typography()
            fun TextStyle.f(ff: FontFamily) = copy(fontFamily = ff)
            t.copy(
                displayLarge = t.displayLarge.f(tokens.display),
                displayMedium = t.displayMedium.f(tokens.display),
                displaySmall = t.displaySmall.f(tokens.display),
                headlineLarge = t.headlineLarge.f(tokens.display),
                headlineMedium = t.headlineMedium.f(tokens.display),
                headlineSmall = t.headlineSmall.f(tokens.display),
                titleLarge = t.titleLarge.f(tokens.display),
                titleMedium = t.titleMedium.f(tokens.body),
                titleSmall = t.titleSmall.f(tokens.body),
                bodyLarge = t.bodyLarge.f(tokens.body),
                bodyMedium = t.bodyMedium.f(tokens.body),
                bodySmall = t.bodySmall.f(tokens.body),
                labelLarge = t.labelLarge.f(tokens.body),
                labelMedium = t.labelMedium.f(tokens.body),
                labelSmall = t.labelSmall.f(tokens.body),
            )
        }
    CompositionLocalProvider(LocalAppsTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
