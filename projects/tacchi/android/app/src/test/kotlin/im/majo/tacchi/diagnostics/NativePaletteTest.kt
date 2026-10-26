package im.majo.tacchi.diagnostics

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePaletteTest {
    @Test fun lightAndDarkTextPairsMeetNormalTextContrast() {
        for (variant in listOf("values", "values-night")) {
            val palette = File("src/main/res/$variant/colors.xml")
            val entries = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(palette)
                .getElementsByTagName("color")
            val colors = (0 until entries.length).associate { index ->
                val entry = entries.item(index)
                entry.attributes.getNamedItem("name").nodeValue to entry.textContent.trim().removePrefix("#").toInt(16)
            }
            for (foreground in listOf("text_primary", "text_secondary")) {
                for (background in listOf("page_background", "card_background")) {
                    assertContrast(variant, foreground, background, colors)
                }
            }
            assertContrast(variant, "on_accent", "accent", colors)
        }
    }

    private fun assertContrast(variant: String, foreground: String, background: String, colors: Map<String, Int>) {
        val a = luminance(colors.getValue(foreground))
        val b = luminance(colors.getValue(background))
        val ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
        assertTrue("$variant $foreground on $background has contrast $ratio", ratio >= 4.5)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
