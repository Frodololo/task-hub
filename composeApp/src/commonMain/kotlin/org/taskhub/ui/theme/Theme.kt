/**
 * Sistema de temas de Task Hub: paletas de color (base + esquemas
 * claro/oscuro de Material3), tipografía y el composable [TaskHubTheme] que
 * los aplica. Los colores semánticos (éxito/aviso/info) viven aparte en
 * [SemanticColors.kt].
 */
package org.taskhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Theme enum ────────────────────────────────────────────

/**
 * Los 6 temas visuales que el usuario elige en Ajustes (persistido en
 * [org.taskhub.storage.SettingsStore], ver `App.kt`). Cada uno tiene su
 * propio par de esquemas Material3 claro/oscuro más abajo (p.ej.
 * [DefaultLightColorScheme]/[DefaultDarkColorScheme] para [DEFAULT]).
 */
enum class TaskHubThemeType {
    /** Paleta de marca: teal + acentos coral. */
    DEFAULT,
    /** Paleta verde/marrón ("Naturaleza"). */
    NATURALEZA,
    /** Paleta monocroma blanco/negro/grises. */
    MINIMAL,
    /** Azul profundo + cian + arena cálida (profesional, calmado). */
    OCEANO,
    /** Púrpura + naranja + rosa (enérgico, atardecer). */
    ATARDECER,
    /** Azul noche + eléctrico + plata (premium, oscuro incluso en modo claro). */
    MIDNIGHT
}

// ── Colores base ──────────────────────────────────────────

// === Marca Task Hub: paleta verde menta / teal con acentos cálidos ===

// Colores primarios: teal (verde azulado, fresco y moderno)
val Teal50 = Color(0xFFE0F7F4)
val Teal100 = Color(0xFFB2EBE2)
val Teal200 = Color(0xFF80D8CC)
val Teal300 = Color(0xFF4DC6B5)
val Teal400 = Color(0xFF26B6A3)
val Teal500 = Color(0xFF00A693)
val Teal600 = Color(0xFF009884)
val Teal700 = Color(0xFF008772)
val Teal800 = Color(0xFF007660)
val Teal900 = Color(0xFF005A48)

// Acento cálido: coral / naranja suave (para botones de acción, CTAs)
val Coral50 = Color(0xFFFFF0EC)
val Coral100 = Color(0xFFFFD8CF)
val Coral200 = Color(0xFFFFB8A8)
val Coral300 = Color(0xFFFF9580)
val Coral400 = Color(0xFFFF775D)
val Coral500 = Color(0xFFFF5C3A)
val Coral600 = Color(0xFFE64A2E)
val Coral700 = Color(0xFFB33A22)
val Coral800 = Color(0xFF802B18)
val Coral900 = Color(0xFF4D1A0E)

// Superficies
val Sand50 = Color(0xFFFEFCF8)
val Sand100 = Color(0xFFFDF6EE)

// ── Naturaleza colors ─────────────────────────────────────

val Green50 = Color(0xFFE8F5E9)
val Green100 = Color(0xFFC8E6C9)
val Green200 = Color(0xFFA5D6A7)
val Green300 = Color(0xFF81C784)
val Green400 = Color(0xFF66BB6A)
val Green500 = Color(0xFF4CAF50)
val Green600 = Color(0xFF43A047)
val Green700 = Color(0xFF388E3C)
val Green800 = Color(0xFF2E7D32)
val Green900 = Color(0xFF1B5E20)

val Brown50 = Color(0xFFEFEBE9)
val Brown100 = Color(0xFFD7CCC8)
val Brown200 = Color(0xFFBCAAA4)
val Brown300 = Color(0xFFA1887F)
val Brown400 = Color(0xFF8D6E63)
val Brown500 = Color(0xFF795548)
val Brown600 = Color(0xFF6D4C41)
val Brown700 = Color(0xFF5D4037)
val Brown800 = Color(0xFF4E342E)
val Brown900 = Color(0xFF3E2723)

val Earth50 = Color(0xFFF9F5F0)
val Earth100 = Color(0xFFF0E6D8)

// ── Variantes ajustadas para contraste WCAG AA (>=4.5:1) ──
// Estas NO sustituyen a los swatches de arriba (que se siguen usando tal
// cual en el resto de la UI): son shades ligeramente más oscuros que sólo
// se usan en los roles de colorScheme donde el swatch original quedaba por
// debajo de 4.5:1 frente a su "on" color emparejado.
private val TealOnPrimaryDarkAA = Color(0xFF004F3F) // era Teal900 (005A48), 3.94:1 sobre Teal300 -> 4.59:1
private val TealPrimaryContainerDarkAA = Color(0xFF00715C) // era Teal800 (007660), 4.22:1 sobre Teal100 -> 4.51:1
private val TealSecondaryContainerDarkAA = Color(0xFF007C69) // era Teal700 (008772), 3.99:1 sobre Teal50 -> 4.60:1
private val TealOnSecondaryContainerLightAA = Color(0xFF007C69) // era Teal700 (008772), 3.99:1 sobre Teal50 -> 4.60:1
private val GreenOnPrimaryDarkAA = Color(0xFF18531C) // era Green900 (1B5E20), 3.91:1 sobre Green300 -> 4.55:1
private val GreenPrimaryContainerDarkAA = Color(0xFF29702D) // era Green800 (2E7D32), 3.81:1 sobre Green100 -> 4.53:1
private val GreenTertiaryContainerDarkAA = Color(0xFF317C35) // era Green700 (388E3C), 3.66:1 sobre Green50 -> 4.59:1
private val GreenTertiaryLightAA = Color(0xFF358639) // era Green700 (388E3C), 4.12:1 sobre blanco -> 4.54:1
private val BrownOnSecondaryDarkAA = Color(0xFF36221F) // era Brown900 (3E2723), 4.18:1 sobre Brown300 -> 4.51:1

// ── Minimal colors ────────────────────────────────────────

val MonoWhite = Color(0xFFFFFFFF)
val MonoGray50 = Color(0xFFF5F5F5)
val MonoGray100 = Color(0xFFE0E0E0)
val MonoGray200 = Color(0xFFBDBDBD)
val MonoGray400 = Color(0xFF757575)
val MonoGray600 = Color(0xFF424242)
val MonoGray800 = Color(0xFF212121)
val MonoGray900 = Color(0xFF121212)
val MonoBlack = Color(0xFF000000)

// ── Océano colors (azul profundo + cian + arena) ──────────

val OceanBlue50 = Color(0xFFE3F2FD)
val OceanBlue100 = Color(0xFFBBDEFB)
val OceanBlue200 = Color(0xFF90CAF9)
val OceanBlue300 = Color(0xFF64B5F6)
val OceanBlue400 = Color(0xFF42A5F5)
val OceanBlue500 = Color(0xFF2196F3)
val OceanBlue600 = Color(0xFF1E88E5)
val OceanBlue700 = Color(0xFF1976D2)
val OceanBlue800 = Color(0xFF1565C0)
val OceanBlue900 = Color(0xFF0D47A1)

val OceanCyan50 = Color(0xFFE0F7FA)
val OceanCyan100 = Color(0xFFB2EBF2)
val OceanCyan200 = Color(0xFF80DEEA)
val OceanCyan300 = Color(0xFF4DD0E1)
val OceanCyan400 = Color(0xFF26C6DA)
val OceanCyan500 = Color(0xFF00BCD4)
val OceanCyan600 = Color(0xFF00ACC1)
val OceanCyan700 = Color(0xFF0097A7)
val OceanCyan800 = Color(0xFF00838F)
val OceanCyan900 = Color(0xFF006064)

val OceanSand = Color(0xFFFAF3E7)
val OceanSandDark = Color(0xFF15222E)

// Variantes ajustadas para contraste WCAG AA (>=4.5:1 texto normal, >=3:1 no-textual)
private val OceanOnSecondaryLightAA = Color(0xFF001A20) // blanco sobre OceanCyan700 daba 3.51:1 -> 5.12:1
private val OceanOnTertiaryLightAA = Color(0xFF08243D) // blanco sobre OceanBlue500 daba 3.12:1 -> 5.05:1
private val OceanSurfaceVariantLight = Color(0xFFF0E6D2)
private val OceanOnSurfaceVariantLightAA = Color(0xFF45505A) // 6.65:1 sobre OceanSurfaceVariantLight
private val OceanOutlineLight = Color(0xFF6B7680) // 4.20:1 sobre OceanSand (uso no-textual, umbral 3:1)
private val OceanOutlineVariantLight = Color(0xFFD8CBAE)
private val OceanOnPrimaryDarkAA = Color(0xFF0D2C54) // 6.30:1 sobre OceanBlue300
private val OceanOnSecondaryDarkAA = Color(0xFF003940) // 6.89:1 sobre OceanCyan300
private val OceanOnTertiaryDarkAA = Color(0xFF08243D) // 9.02:1 sobre OceanBlue200
private val OceanOnSurfaceVariantDarkAA = Color(0xFFCAD6E0) // 10.93:1 sobre OceanSandDark
private val OceanOutlineDarkAA = Color(0xFF8FB8DE) // 4.14:1 sobre OceanBlue900 (no-textual)
private val OceanOutlineVariantDark = Color(0xFF2A4A75)

// ── Atardecer colors (púrpura + naranja + rosa) ───────────

val SunsetPurple50 = Color(0xFFF3E5F5)
val SunsetPurple100 = Color(0xFFE1BEE7)
val SunsetPurple200 = Color(0xFFCE93D8)
val SunsetPurple300 = Color(0xFFBA68C8)
val SunsetPurple400 = Color(0xFFAB47BC)
val SunsetPurple500 = Color(0xFF9C27B0)
val SunsetPurple600 = Color(0xFF8E24AA)
val SunsetPurple700 = Color(0xFF7B1FA2)
val SunsetPurple800 = Color(0xFF6A1B9A)
val SunsetPurple900 = Color(0xFF4A148C)

val SunsetOrange50 = Color(0xFFFFF3E0)
val SunsetOrange100 = Color(0xFFFFE0B2)
val SunsetOrange200 = Color(0xFFFFCC80)
val SunsetOrange300 = Color(0xFFFFB74D)
val SunsetOrange400 = Color(0xFFFFA726)
val SunsetOrange500 = Color(0xFFFF9800)
val SunsetOrange600 = Color(0xFFFB8C00)
val SunsetOrange700 = Color(0xFFF57C00)
val SunsetOrange800 = Color(0xFFEF6C00)
val SunsetOrange900 = Color(0xFFE65100)

val SunsetPink = Color(0xFFFF6FA5)
val SunsetPinkDark = Color(0xFFC2185B)
val SunsetCream = Color(0xFFFFF6EC)
val SunsetCreamDark = Color(0xFF2A1F33)

// Variantes ajustadas para contraste WCAG AA
private val SunsetOnSecondaryLightAA = Color(0xFF3D0A00) // 6.25:1 sobre SunsetOrange700 (blanco daba 2.70:1)
private val SunsetOnSecondaryContainerLightAA = Color(0xFF7A3400) // 7.14:1 sobre SunsetOrange100
private val SunsetTertiaryContainerLight = Color(0xFFFFE0EC)
private val SunsetSurfaceVariantLight = Color(0xFFF3E5DC)
private val SunsetOnSurfaceVariantLightAA = Color(0xFF5A4A52) // 6.72:1 sobre SunsetSurfaceVariantLight
private val SunsetOutlineLight = Color(0xFF8A7A82) // 3.79:1 sobre SunsetCream (no-textual)
private val SunsetOutlineVariantLight = Color(0xFFD8C8CE)
private val SunsetOnPrimaryDarkAA = Color(0xFF200530) // 5.24:1 sobre SunsetPurple300
private val SunsetOnSecondaryDarkAA = Color(0xFF4A2800) // 7.61:1 sobre SunsetOrange300
private val SunsetOnTertiaryDarkAA = Color(0xFF4A0022) // 6.14:1 sobre SunsetPink
private val SunsetSecondaryContainerDarkAA = Color(0xFF7A3400)
private val SunsetTertiaryContainerDarkAA = Color(0xFF8E0038)
private val SunsetOnBackgroundDark = Color(0xFFF0E5F5)
private val SunsetSurfaceVariantDark = Color(0xFF2E2038)
private val SunsetOnSurfaceVariantDarkAA = Color(0xFFD8C8DE) // 9.59:1 sobre SunsetSurfaceVariantDark
private val SunsetOutlineDarkAA = Color(0xFFC9A8D6) // 8.75:1 sobre background dark (no-textual)
private val SunsetOutlineVariantDark = Color(0xFF4A3A56)

// ── Midnight colors (azul noche + eléctrico + plata) ──────

val MidnightNavy = Color(0xFF0B1120)
val MidnightNavyLight = Color(0xFF141C30)
val MidnightNavySurface = Color(0xFF10182A)
val MidnightNavyCard = Color(0xFF1A2438)

val MidnightBlue100 = Color(0xFFBBD6FF)
val MidnightBlue200 = Color(0xFF8AB4FF)
val MidnightBlue300 = Color(0xFF5C93FF)
val MidnightBlue400 = Color(0xFF2E72FF)
val MidnightBlue500 = Color(0xFF0052FF)
val MidnightBlue600 = Color(0xFF0044D6)
val MidnightBlue700 = Color(0xFF0036AD)
val MidnightBlue800 = Color(0xFF002985)
val MidnightBlue900 = Color(0xFF001C5C)

val MidnightSilver50 = Color(0xFFF2F4F7)
val MidnightSilver100 = Color(0xFFE1E5EB)
val MidnightSilver200 = Color(0xFFC7CEDA)
val MidnightSilver300 = Color(0xFFA9B3C4)
val MidnightSilver400 = Color(0xFF8B96AC)
val MidnightSilver500 = Color(0xFF707C94)
val MidnightSilver600 = Color(0xFF5A6478)
val MidnightSilver800 = Color(0xFF2A3040)

// Variantes ajustadas para contraste WCAG AA
private val MidnightOnTertiaryLight = Color(0xFF000000) // 4.97:1 sobre MidnightBlue400 (blanco daba 4.23:1)
private val MidnightOnBackgroundLight = Color(0xFFF2F4F7)
private val MidnightOnPrimaryDarkAA = Color(0xFF001238) // 6.18:1 sobre MidnightBlue300
private val MidnightOnSecondaryDarkAA = Color(0xFF10182A) // 8.37:1 sobre MidnightSilver300
private val MidnightOnTertiaryDarkAA = Color(0xFF001238) // 8.79:1 sobre MidnightBlue200

// ── Default schemes ───────────────────────────────────────

private val DefaultLightColorScheme = lightColorScheme(
    primary = Teal800,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,

    secondary = Teal800,
    onSecondary = Color.White,
    secondaryContainer = Teal50,
    onSecondaryContainer = TealOnSecondaryContainerLightAA,

    tertiary = Coral700,
    onTertiary = Color.White,
    tertiaryContainer = Coral100,
    onTertiaryContainer = Coral800,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Sand50,
    onBackground = Color(0xFF1C1B1F),
    surface = Sand50,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Sand100,
    onSurfaceVariant = Color(0xFF49454F),

    // Panel v16 (2026-09-24), hallazgo accesibilidad: 0xFF79747E sobre
    // Sand50 daba 4.44:1 — por debajo del umbral AA de texto normal (4.5:1),
    // aunque el único uso actual (StatusDot en TaskListScreen) es no-textual
    // (umbral 3:1). Se oscurece para blindar el token si se reutiliza como
    // texto en el futuro: 5.45:1 verificado sobre Sand50.
    outline = Color(0xFF6B6670),
    outlineVariant = Color(0xFFCAC4D0),
)

private val DefaultDarkColorScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = TealOnPrimaryDarkAA,
    primaryContainer = TealPrimaryContainerDarkAA,
    onPrimaryContainer = Teal100,

    secondary = Teal200,
    onSecondary = Teal900,
    secondaryContainer = TealSecondaryContainerDarkAA,
    onSecondaryContainer = Teal50,

    tertiary = Coral300,
    onTertiary = Coral900,
    tertiaryContainer = Coral700,
    onTertiaryContainer = Coral100,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2C2F),
    onSurfaceVariant = Color(0xFFCAC4D0),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
)

// ── Naturaleza schemes (verdes, marrones) ─────────────────

private val NaturalezaLightColorScheme = lightColorScheme(
    primary = Green800,
    onPrimary = Color.White,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,

    secondary = Brown500,
    onSecondary = Color.White,
    secondaryContainer = Brown100,
    onSecondaryContainer = Brown900,

    tertiary = GreenTertiaryLightAA,
    onTertiary = Color.White,
    tertiaryContainer = Green50,
    onTertiaryContainer = Green800,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Earth50,
    onBackground = Brown900,
    surface = Earth50,
    onSurface = Brown900,
    surfaceVariant = Earth100,
    onSurfaceVariant = Brown700,

    // Panel v16, hallazgo accesibilidad: 0xFF8D6E63 (= Brown400) sobre
    // Earth50 daba 4.26:1 — mismo motivo que el tema DEFAULT de arriba.
    // Brown500 da 6.03:1 verificado sobre Earth50.
    outline = Brown500,
    outlineVariant = Color(0xFFBCAAA4),
)

private val NaturalezaDarkColorScheme = darkColorScheme(
    primary = Green300,
    onPrimary = GreenOnPrimaryDarkAA,
    primaryContainer = GreenPrimaryContainerDarkAA,
    onPrimaryContainer = Green100,

    secondary = Brown300,
    onSecondary = BrownOnSecondaryDarkAA,
    secondaryContainer = Brown700,
    onSecondaryContainer = Brown100,

    tertiary = Green200,
    onTertiary = Green900,
    tertiaryContainer = GreenTertiaryContainerDarkAA,
    onTertiaryContainer = Green50,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF1B1A18),
    onBackground = Color(0xFFE6E2DD),
    surface = Color(0xFF1B1A18),
    onSurface = Color(0xFFE6E2DD),
    surfaceVariant = Color(0xFF2D2A25),
    onSurfaceVariant = Color(0xFFCBC5BA),

    outline = Color(0xFF958F86),
    outlineVariant = Color(0xFF4A453D),
)

// ── Minimal schemes (blanco y negro) ──────────────────────

private val MinimalLightColorScheme = lightColorScheme(
    primary = MonoGray800,
    onPrimary = MonoWhite,
    primaryContainer = MonoGray100,
    onPrimaryContainer = MonoGray900,

    secondary = MonoGray600,
    onSecondary = MonoWhite,
    secondaryContainer = MonoGray50,
    onSecondaryContainer = MonoGray800,

    tertiary = MonoGray400,
    onTertiary = MonoWhite,
    tertiaryContainer = MonoGray50,
    onTertiaryContainer = MonoGray600,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = MonoWhite,
    onBackground = MonoGray900,
    surface = MonoWhite,
    onSurface = MonoGray900,
    surfaceVariant = MonoGray50,
    // Panel v24 (2026-10-01), hallazgo accesibilidad: MonoGray600 (#757575)
    // sobre MonoGray50 (#F5F5F5) daba 4.23:1 — por debajo del umbral AA de
    // texto normal (4.5:1). #707070 da 4.54:1.
    onSurfaceVariant = Color(0xFF707070),

    // Panel v24 (2026-10-01), hallazgo accesibilidad: MonoGray200 (#BDBDBD)
    // daba 1.88:1 sobre fondo blanco — por debajo del umbral 3.0:1 para
    // contenido no-textual (WCAG 1.4.11). #8E8E8E da 3.28:1.
    outline = Color(0xFF8E8E8E),
    outlineVariant = MonoGray200,
)

private val MinimalDarkColorScheme = darkColorScheme(
    primary = MonoGray100,
    onPrimary = MonoGray900,
    // Panel v24 (2026-10-01), hallazgo accesibilidad: MonoGray50 (#F5F5F5)
    // sobre MonoGray600 (#757575) daba 4.23:1 — por debajo del umbral AA de
    // texto normal (4.5:1). Blanco (#FFFFFF) da 4.61:1.
    primaryContainer = MonoGray600,
    onPrimaryContainer = MonoWhite,

    secondary = MonoGray200,
    onSecondary = MonoGray800,
    secondaryContainer = MonoGray600,
    onSecondaryContainer = MonoWhite,

    tertiary = MonoGray400,
    onTertiary = MonoWhite,
    tertiaryContainer = MonoGray600,
    onTertiaryContainer = MonoWhite,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = MonoBlack,
    onBackground = MonoGray100,
    surface = MonoBlack,
    onSurface = MonoGray100,
    surfaceVariant = MonoGray800,
    // MonoGray200 (no MonoGray400): MonoGray400 sobre MonoGray800 da 3.49:1,
    // por debajo del umbral WCAG AA de texto normal (4.5:1) — es el color
    // "secundario"/caption por defecto del tema Minimal oscuro completo, no
    // un caso aislado. MonoGray200 da 8.57:1.
    onSurfaceVariant = MonoGray200,

    outline = MonoGray400,
    outlineVariant = MonoGray600,
)

// ── Océano schemes (azul + cian + arena) ──────────────────

private val OceanoLightColorScheme = lightColorScheme(
    primary = OceanBlue800,
    onPrimary = Color.White,
    primaryContainer = OceanBlue100,
    onPrimaryContainer = OceanBlue900,

    secondary = OceanCyan700,
    onSecondary = OceanOnSecondaryLightAA,
    secondaryContainer = OceanCyan100,
    onSecondaryContainer = OceanCyan900,

    tertiary = OceanBlue500,
    onTertiary = OceanOnTertiaryLightAA,
    tertiaryContainer = OceanBlue50,
    onTertiaryContainer = OceanBlue800,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = OceanSand,
    onBackground = Color(0xFF1A2733),
    surface = OceanSand,
    onSurface = Color(0xFF1A2733),
    surfaceVariant = OceanSurfaceVariantLight,
    onSurfaceVariant = OceanOnSurfaceVariantLightAA,

    outline = OceanOutlineLight,
    outlineVariant = OceanOutlineVariantLight,
)

private val OceanoDarkColorScheme = darkColorScheme(
    primary = OceanBlue300,
    onPrimary = OceanOnPrimaryDarkAA,
    primaryContainer = OceanBlue800,
    onPrimaryContainer = OceanBlue50,

    secondary = OceanCyan300,
    onSecondary = OceanOnSecondaryDarkAA,
    secondaryContainer = OceanCyan900,
    onSecondaryContainer = OceanCyan50,

    tertiary = OceanBlue200,
    onTertiary = OceanOnTertiaryDarkAA,
    tertiaryContainer = OceanBlue700,
    onTertiaryContainer = Color.White,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = OceanBlue900,
    onBackground = OceanBlue50,
    surface = OceanBlue900,
    onSurface = OceanBlue50,
    surfaceVariant = OceanSandDark,
    onSurfaceVariant = OceanOnSurfaceVariantDarkAA,

    outline = OceanOutlineDarkAA,
    outlineVariant = OceanOutlineVariantDark,
)

// ── Atardecer schemes (púrpura + naranja + rosa) ──────────

private val AtardecerLightColorScheme = lightColorScheme(
    primary = SunsetPurple800,
    onPrimary = Color.White,
    primaryContainer = SunsetPurple100,
    onPrimaryContainer = SunsetPurple900,

    secondary = SunsetOrange700,
    onSecondary = SunsetOnSecondaryLightAA,
    secondaryContainer = SunsetOrange100,
    onSecondaryContainer = SunsetOnSecondaryContainerLightAA,

    tertiary = SunsetPinkDark,
    onTertiary = Color.White,
    tertiaryContainer = SunsetTertiaryContainerLight,
    onTertiaryContainer = SunsetPinkDark,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = SunsetCream,
    onBackground = Color(0xFF1A1028),
    surface = SunsetCream,
    onSurface = Color(0xFF1A1028),
    surfaceVariant = SunsetSurfaceVariantLight,
    onSurfaceVariant = SunsetOnSurfaceVariantLightAA,

    outline = SunsetOutlineLight,
    outlineVariant = SunsetOutlineVariantLight,
)

private val AtardecerDarkColorScheme = darkColorScheme(
    primary = SunsetPurple300,
    onPrimary = SunsetOnPrimaryDarkAA,
    primaryContainer = SunsetPurple700,
    onPrimaryContainer = SunsetPurple100,

    secondary = SunsetOrange300,
    onSecondary = SunsetOnSecondaryDarkAA,
    secondaryContainer = SunsetSecondaryContainerDarkAA,
    onSecondaryContainer = SunsetOrange100,

    tertiary = SunsetPink,
    onTertiary = SunsetOnTertiaryDarkAA,
    tertiaryContainer = SunsetTertiaryContainerDarkAA,
    onTertiaryContainer = SunsetTertiaryContainerLight,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF1A1028),
    onBackground = SunsetOnBackgroundDark,
    surface = Color(0xFF1A1028),
    onSurface = SunsetOnBackgroundDark,
    surfaceVariant = SunsetSurfaceVariantDark,
    onSurfaceVariant = SunsetOnSurfaceVariantDarkAA,

    outline = SunsetOutlineDarkAA,
    outlineVariant = SunsetOutlineVariantDark,
)

// ── Midnight schemes (azul noche + eléctrico + plata) ─────

private val MidnightLightColorScheme = lightColorScheme(
    primary = MidnightBlue500,
    onPrimary = Color.White,
    primaryContainer = MidnightNavyCard,
    onPrimaryContainer = MidnightBlue200,

    secondary = MidnightSilver600,
    onSecondary = Color.White,
    secondaryContainer = MidnightNavyLight,
    onSecondaryContainer = MidnightSilver200,

    tertiary = MidnightBlue400,
    onTertiary = MidnightOnTertiaryLight,
    tertiaryContainer = MidnightNavyCard,
    onTertiaryContainer = MidnightBlue100,

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Fondo oscuro incluso en modo claro: es la estética "premium" del tema.
    background = MidnightNavy,
    onBackground = MidnightOnBackgroundLight,
    surface = MidnightNavySurface,
    onSurface = MidnightOnBackgroundLight,
    surfaceVariant = MidnightNavyLight,
    onSurfaceVariant = MidnightSilver300,

    outline = MidnightSilver500,
    outlineVariant = MidnightSilver800,
)

private val MidnightDarkColorScheme = darkColorScheme(
    primary = MidnightBlue300,
    onPrimary = MidnightOnPrimaryDarkAA,
    primaryContainer = MidnightBlue800,
    onPrimaryContainer = MidnightBlue100,

    secondary = MidnightSilver300,
    onSecondary = MidnightOnSecondaryDarkAA,
    secondaryContainer = MidnightSilver800,
    onSecondaryContainer = MidnightSilver100,

    tertiary = MidnightBlue200,
    onTertiary = MidnightOnTertiaryDarkAA,
    tertiaryContainer = MidnightBlue700,
    onTertiaryContainer = MidnightBlue100,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF03050A),
    onBackground = MidnightSilver50,
    surface = Color(0xFF03050A),
    onSurface = MidnightSilver50,
    surfaceVariant = MidnightNavyCard,
    onSurfaceVariant = MidnightSilver200,

    outline = MidnightSilver500,
    outlineVariant = MidnightSilver800,
)

// ── Tipografía ────────────────────────────────────────────

private val DefaultTypography = Typography()

// Typography(...) con TextStyle(...) sueltos NO hereda fontSize/lineHeight del
// type-scale M3 base — cada rol que solo especifica peso/tracking queda con
// esos campos en Unspecified y Compose los resuelve a 14sp por defecto en
// layout. Por eso cada rol parte de DefaultTypography.copy(...).
private val TaskHubTypography = DefaultTypography.copy(
    headlineLarge = DefaultTypography.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = DefaultTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = DefaultTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    labelSmall = DefaultTypography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

// ── Theme composable ──────────────────────────────────────

/**
 * Aplica el tema Material3 correspondiente a [themeType] (claro u oscuro
 * según [darkTheme], que por defecto sigue al sistema operativo) y provee
 * los [SemanticColors] a juego vía [LocalSemanticColors]. Envuelve el árbol
 * de Compose entero — se instala una única vez en `App.kt` (y en
 * `Main.kt`/`MainViewController.kt` para el splash previo a `App`).
 */
@Composable
fun TaskHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeType: TaskHubThemeType = TaskHubThemeType.DEFAULT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeType) {
        TaskHubThemeType.DEFAULT -> if (darkTheme) DefaultDarkColorScheme else DefaultLightColorScheme
        TaskHubThemeType.NATURALEZA -> if (darkTheme) NaturalezaDarkColorScheme else NaturalezaLightColorScheme
        TaskHubThemeType.MINIMAL -> if (darkTheme) MinimalDarkColorScheme else MinimalLightColorScheme
        TaskHubThemeType.OCEANO -> if (darkTheme) OceanoDarkColorScheme else OceanoLightColorScheme
        TaskHubThemeType.ATARDECER -> if (darkTheme) AtardecerDarkColorScheme else AtardecerLightColorScheme
        TaskHubThemeType.MIDNIGHT -> if (darkTheme) MidnightDarkColorScheme else MidnightLightColorScheme
    }

    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TaskHubTypography,
            content = content
        )
    }
}