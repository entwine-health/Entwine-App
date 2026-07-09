// Entwine brand theme — tokens mirror the website palette 1:1 (S3
// entwine-website/index.html :root). Dark-only by design: black canvas,
// cyan "structural data" accent, coral "behavioral signal" accent.
// Crisis UI intentionally stays OFF-brand (hardcoded red, Screens.kt) so it
// never reads as a normal screen.
package health.entwine.lucy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Website :root tokens.
val EntwineCyan = Color(0xFF00E5FF) // --brand-cyan
val EntwineCoral = Color(0xFFFF453A) // --brand-coral
val EntwineYellow = Color(0xFFFFCC00) // transition accent (ON/SHIFTING/OFF chart)
val EntwineDim = Color(0xFF8E8E93) // --text-dimmed
val EntwineCard = Color(0xFF0A0A0C) // --card-inner
val EntwineBorder = Color(0xFF1C1C1E) // --border-gray

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

/** App-wide Material3 theme carrying the Entwine brand palette. */
@Composable
fun EntwineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
