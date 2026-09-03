package app.ageha.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every token Ageha defines, rendered in all three themes at once.
 *
 * Side by side rather than behind a theme switch, on purpose. The questions this screen exists to
 * answer -- is the AMOLED surface ramp still legible, does the vermillion hold up on sumi as well
 * as on paper, is the dark primary actually light enough -- are *comparisons*, and a toggle makes
 * you hold the previous state in your head. Three columns makes the comparison the default.
 *
 * Every foreground/background pair prints its live WCAG contrast ratio and fails visibly below
 * 4.5:1. The brief says these must pass AA and says not to eyeball it; `AgehaContrastTest` is the
 * enforcement, and this is the same check made visible so a reviewer can see *how much* headroom
 * a pair has rather than just that it passed.
 */
@Composable
fun ThemeGallery(modifier: Modifier = Modifier) {
	val scroll = rememberScrollState()
	Surface(color = Color(0xFF6E6E72), modifier = modifier) {
		Column(
			modifier = Modifier.verticalScroll(scroll).padding(24.dp),
			verticalArrangement = Arrangement.spacedBy(28.dp),
		) {
			GalleryTitle()
			GallerySection("Colour roles") { ThemedTriple { ColourRoles() } }
			GallerySection("Surface ramp") { ThemedTriple { SurfaceRamp() } }
			GallerySection("Type scale") { ThemedTriple { TypeScale() } }
			GallerySection("The vermillion accent, and its only permitted uses") {
				ThemedTriple { AccentUses() }
			}
			GallerySection("Reader backgrounds -- theme-independent, and brand-free by rule") {
				ReaderBackgrounds()
			}
			GallerySection("Spacing, shape and motion") { ThemedTriple { ScaleTokens() } }
			GallerySection("Icon crossover: simplified mark vs downscaled master") { IconLadder() }
			GallerySection("Fonts resolved on this machine") { FontReport() }
		}
	}
}

@Composable
private fun GalleryTitle() {
	Column {
		Text(
			"Ageha design system",
			color = Color.White,
			fontSize = 26.sp,
			fontWeight = FontWeight.Medium,
		)
		Text(
			"Seed #2B3A67 indigo - accent #B93723 vermillion sampled from the seal - " +
				"paper #F5F1E8 - sumi #1A1A1D",
			color = Color(0xFFD8D8DC),
			fontSize = 12.sp,
		)
	}
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
		content()
	}
}

/** The same content, once per theme, in three columns. */
@Composable
private fun ThemedTriple(content: @Composable () -> Unit) {
	Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		for (mode in listOf(AgehaThemeMode.LIGHT, AgehaThemeMode.DARK, AgehaThemeMode.AMOLED)) {
			AgehaTheme(mode = mode) {
				Surface(
					modifier = Modifier.width(360.dp).clip(MaterialTheme.shapes.medium),
					color = MaterialTheme.colorScheme.background,
				) {
					Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							mode.name.lowercase().replaceFirstChar { it.uppercase() },
							style = MaterialTheme.typography.titleSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						content()
					}
				}
			}
		}
	}
}

// ------------------------------------------------------------------ colour

/** WCAG 2.1 contrast. Compose's `luminance()` is already the relative luminance WCAG defines. */
private fun contrast(a: Color, b: Color): Double {
	val la = a.luminance().toDouble()
	val lb = b.luminance().toDouble()
	return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun Color.hex(): String =
	"#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

@Composable
private fun ColourRoles() {
	val c = MaterialTheme.colorScheme
	val pairs = listOf(
		Triple("primary", c.primary, c.onPrimary),
		Triple("primaryContainer", c.primaryContainer, c.onPrimaryContainer),
		Triple("secondary", c.secondary, c.onSecondary),
		Triple("secondaryContainer", c.secondaryContainer, c.onSecondaryContainer),
		Triple("tertiary", c.tertiary, c.onTertiary),
		Triple("tertiaryContainer", c.tertiaryContainer, c.onTertiaryContainer),
		Triple("error", c.error, c.onError),
		Triple("errorContainer", c.errorContainer, c.onErrorContainer),
		Triple("background", c.background, c.onBackground),
		Triple("surface", c.surface, c.onSurface),
		Triple("surfaceVariant", c.surfaceVariant, c.onSurfaceVariant),
		Triple("inverseSurface", c.inverseSurface, c.inverseOnSurface),
	)
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		for ((name, bg, fg) in pairs) ColourPair(name, bg, fg)
	}
}

@Composable
private fun ColourPair(name: String, background: Color, foreground: Color) {
	val ratio = contrast(foreground, background)
	val passes = ratio >= 4.5
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(3.dp))
			.background(background)
			.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(name, color = foreground, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
		Text(background.hex(), color = foreground, style = AgehaTextStyles.readerHud)
		Spacer(Modifier.width(8.dp))
		// The ratio is printed for every pair, passing or not. A pair that clears AA at 4.6 is a
		// different thing from one that clears it at 12, and only one of them survives a future
		// tweak to the palette unnoticed.
		Text(
			text = (if (passes) "AA " else "FAIL ") + "%.1f".format(ratio),
			color = foreground,
			style = AgehaTextStyles.readerHud,
			fontWeight = if (passes) FontWeight.Normal else FontWeight.Bold,
		)
	}
}

@Composable
private fun SurfaceRamp() {
	val c = MaterialTheme.colorScheme
	val ramp = listOf(
		"surfaceDim" to c.surfaceDim,
		"surfaceContainerLowest" to c.surfaceContainerLowest,
		"surfaceContainerLow" to c.surfaceContainerLow,
		"surfaceContainer" to c.surfaceContainer,
		"surfaceContainerHigh" to c.surfaceContainerHigh,
		"surfaceContainerHighest" to c.surfaceContainerHighest,
		"surfaceBright" to c.surfaceBright,
	)
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		for ((name, colour) in ramp) {
			Row(
				modifier = Modifier.fillMaxWidth().background(colour).padding(horizontal = 8.dp, vertical = 5.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(name, color = c.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
				Text(colour.hex(), color = c.onSurfaceVariant, style = AgehaTextStyles.readerHud)
			}
		}
	}
}

// ------------------------------------------------------------------ type

@Composable
private fun TypeScale() {
	val t = MaterialTheme.typography
	val on = MaterialTheme.colorScheme.onSurface
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("Berserk", style = t.headlineMedium, color = on)
		Text("Sousou no Frieren", style = AgehaTextStyles.mangaTitle, color = on)
		// The scripts Ageha will actually meet. Titles are regularly Japanese, Korean and
		// Chinese, so the gallery shows all four rather than pretending Latin is the case to
		// design for. If a script renders as boxes here, this machine is missing a font -- see
		// the font report at the bottom of the gallery.
		Text("葬送のフリーレン", style = AgehaTextStyles.mangaTitle, color = on)
		Text("나 혼자만 레벨업", style = AgehaTextStyles.mangaTitle, color = on)
		Text("鬥破蒼穹", style = AgehaTextStyles.mangaTitle, color = on)
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		Text("bodyMedium - the default reading size for lists and dialogs.", style = t.bodyMedium, color = on)
		Text(
			"metadata - MangaDex - 214 chapters - read 3 days ago",
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text("readerHud - 117 / 240 - tabular figures", style = AgehaTextStyles.readerHud, color = on)
	}
}

// ------------------------------------------------------------------ accent

@Composable
private fun AccentUses() {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			AgehaAccent.UnreadBadge(3)
			AgehaAccent.UnreadBadge(128)
			AgehaAccent.NewChapterDot()
			AgehaAccent.ActiveReadingIndicator()
			Text(
				"small mark = unread / active",
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Button(onClick = {}, colors = AgehaAccent.destructiveButtonColors()) { Text("Delete downloads") }
			Text(
				"filled = destructive",
				style = AgehaTextStyles.metadata,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Button(onClick = {}) { Text("Primary action") }
	}
}

// ------------------------------------------------------------------ reader

@Composable
private fun ReaderBackgrounds() {
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		for (background in ReaderBackground.entries) {
			val chrome = remember(background) { ReaderChrome.forBackground(background) }
			Column(
				modifier = Modifier
					.width(178.dp)
					.clip(RoundedCornerShape(4.dp))
					.background(background.color)
					.padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(background.label, color = background.onColor, fontSize = 12.sp)
				// Stand-in for a manga page. The point of this row is that nothing around it is
				// indigo or vermillion in any theme -- the reader is the one place the brand
				// does not go.
				Box(
					Modifier.fillMaxWidth().height(60.dp)
						.background(if (background.isDark) Color(0xFF3A3A3A) else Color(0xFFCFCFCF)),
				)
				Box(
					Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
						.background(chrome.scrim).padding(horizontal = 6.dp, vertical = 4.dp),
				) {
					Text("117 / 240", color = chrome.content, fontSize = 11.sp)
				}
			}
		}
	}
}

// ------------------------------------------------------------------ scale

@Composable
private fun ScaleTokens() {
	val on = MaterialTheme.colorScheme.onSurface
	val steps = listOf(
		"xxs" to AgehaSpacing.xxs, "xs" to AgehaSpacing.xs, "sm" to AgehaSpacing.sm,
		"md" to AgehaSpacing.md, "lg" to AgehaSpacing.lg, "xl" to AgehaSpacing.xl,
		"xxl" to AgehaSpacing.xxl, "xxxl" to AgehaSpacing.xxxl,
	)
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		for ((name, dp) in steps) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(name, style = AgehaTextStyles.readerHud, color = on, modifier = Modifier.width(34.dp))
				Box(Modifier.width(dp).height(10.dp).background(MaterialTheme.colorScheme.primary))
				Text("$dp", style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			for ((label, shape) in listOf(
				"xs" to MaterialTheme.shapes.extraSmall,
				"sm" to MaterialTheme.shapes.small,
				"md" to MaterialTheme.shapes.medium,
				"lg" to MaterialTheme.shapes.large,
				"cover" to CoverShape,
			)) {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					Box(Modifier.size(34.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer))
					Text(label, style = AgehaTextStyles.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
		Text(
			"motion  instant ${AgehaMotion.INSTANT_MS}ms - quick ${AgehaMotion.QUICK_MS}ms - " +
				"transition ${AgehaMotion.TRANSITION_MS}ms - reader chrome ${AgehaMotion.CHROME_FADE_MS}ms",
			style = AgehaTextStyles.metadata,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

// ------------------------------------------------------------------ icons and fonts

/**
 * The two icon variants at matched sizes, so the crossover point is a decision someone can look
 * at rather than a constant in a build tool.
 */
@Composable
private fun IconLadder() {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		for ((label, prefix) in listOf("simplified" to "simplified", "downscaled master" to "detailed")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(130.dp))
				for (size in listOf(16, 24, 32, 48, 64)) {
					androidx.compose.foundation.Image(
						painter = BrandAssets.painter("$prefix-$size.png"),
						contentDescription = "$label at $size px",
						modifier = Modifier.size(size.dp),
					)
				}
			}
		}
		Text(
			"Ageha ships the simplified mark below 48px and the master at 48 and above.",
			color = Color(0xFFD8D8DC),
			fontSize = 11.sp,
		)
	}
}

@Composable
private fun FontReport() {
	val coverage = remember { FontCoverage.detect() }
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text("serif -> ${coverage.serif}", color = Color.White, fontSize = 12.sp)
		Text("sans  -> ${coverage.sans}", color = Color.White, fontSize = 12.sp)
		for ((script, family) in coverage.cjk) {
			Text(
				"$script -> ${family ?: "NOT INSTALLED - titles in this script will render as boxes"}",
				color = if (family == null) Color(0xFFFFB4A6) else Color(0xFFD8D8DC),
				fontSize = 12.sp,
			)
		}
	}
}
