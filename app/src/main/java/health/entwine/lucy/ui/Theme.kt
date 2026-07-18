// Entwine brand theme — tokens mirror the website palette 1:1 (S3
// entwine-website/index.html :root). Dark-only by design: black canvas,
// cyan "structural data" accent, coral "behavioral signal" accent.
// Crisis UI intentionally stays OFF-brand (hardcoded red, Screens.kt) so it
// never reads as a normal screen.
package health.entwine.lucy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// Website :root tokens.
val EntwineCyan = Color(0xFF00E5FF) // --brand-cyan
val EntwineCoral = Color(0xFFFF453A) // --brand-coral
val EntwineYellow = Color(0xFFFFCC00) // transition accent (ON/SHIFTING/OFF chart)
val EntwineDim = Color(0xFF8E8E93) // --text-dimmed
val EntwineCard = Color(0xFF0A0A0C) // --card-inner
val EntwineBorder = Color(0xFF1C1C1E) // --border-gray
// Deep cyan-tinted disc for the talk orb's "Lucy holds the floor" states —
// derived from the brand cyan at low luminance (2026-07-18 UI round; white
// text on it clears the R-UXA-13 7:1 bar).
val EntwineCyanDeep = Color(0xFF0E2A30)

private val Scheme = darkColorScheme(
    primary = EntwineCyan,
    onPrimary = Color.Black,
    secondary = EntwineDim,
    onSecondary = Color.Black,
    tertiary = EntwineYellow,
    onTertiary = Color.Black,
    error = EntwineCoral,
    onError = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = EntwineCard,
    onSurface = Color.White,
    surfaceVariant = EntwineBorder,
    onSurfaceVariant = Color(0xFFC7C7CC),
    // Reason: web borders (#1C1C1E) vanish on black at arm's length — PD users
    // need visible control boundaries (R-UXA-01), so outlines use brand gray.
    outline = EntwineDim,
    outlineVariant = Color(0xFF3A3A3C),
)

/** App-wide Material3 theme carrying the Entwine brand palette; direction app-owned.
 *
 * # Reason: layout direction follows the SESSION language, never the device
 * locale (R-UXA-19 as amended, R-LNG-01) — an English-locale phone rendered
 * every Hebrew screen left-to-right (found 2026-07-17), and a Hebrew-locale
 * phone must not force an English session RTL. The server's session language
 * is the single source of truth.
 */
@Composable
fun EntwineTheme(lang: String = "he", content: @Composable () -> Unit) {
    val direction = if (lang == "he") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = Scheme, content = content)
    }
}
