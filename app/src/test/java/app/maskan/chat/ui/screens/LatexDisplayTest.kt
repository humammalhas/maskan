package app.maskan.chat.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * LaTeX in a reply, as the bubble shows it. The "measured" cases are answers captured on the
 * Pixel on 2026-10-25 with ReasoningProbeReceiver `--es question64`; the rest pin single rules.
 */
class LatexDisplayTest {

    /** A whole reply, as the provider streamed it (src/test/resources/latex; tests run from app/). */
    private fun measured(name: String) = File("src/test/resources/latex/$name.txt").readText()

    // "A shop sells pens at 7 for 3 dollars. How much do 84 pens cost?" - DeepSeek with thinking
    // switched off, which is when Humam saw it.
    @Test
    fun measuredDeepSeekFlashEnglish() {
        val raw = measured("deepseek-flash-en")
        val expected = raw
            .replace("\\[\n84 \\div 7 = 12\n\\]", "84 ÷ 7 = 12")
            .replace("\\[\n12 \\times 3 = 36\n\\]", "12 × 3 = 36")
            .replace("\\[\n\\boxed{36}\n\\]", "**36**")
        assertEquals(expected, latexForDisplay(raw))
        assertFalse(latexForDisplay(raw).contains('\\'))
    }

    @Test
    fun measuredGptOssEnglish() {
        val raw = measured("gpt-oss-120b-en")
        assertEquals(
            "Each set of 7 pens costs $3.  \n\n84 ÷ 7 = 12 sets of 7 pens.  \n\n12 × $3 = $36.\n\n**$36**",
            latexForDisplay(raw)
        )
    }

    // The same question in Arabic: gpt-oss puts Arabic words inside \text, inside a fraction.
    @Test
    fun measuredGptOssArabic() {
        val raw = measured("gpt-oss-120b-ar")
        val expected = raw
            .replace(
                "\\[\n\\frac{3\\text{ دنانير}}{7\\text{ أقلام}} = \\frac{3}{7}\\text{ دينار للقطعة}\n\\]",
                "(3 دنانير)/(7 أقلام) = 3/7 دينار للقطعة"
            )
            .replace(
                "\\[\n84 \\times \\frac{3}{7}= \\frac{84}{7}\\times 3 = 12 \\times 3 = 36\n\\]",
                "84 × 3/7= 84/7 × 3 = 12 × 3 = 36"
            )
        assertEquals(expected, latexForDisplay(raw))
        assertFalse(latexForDisplay(raw).contains('\\'))
    }

    // DeepSeek answered the Arabic question in plain Unicode: nothing to do, and nothing done.
    @Test
    fun measuredDeepSeekFlashArabicHasNoLatex() {
        val raw = measured("deepseek-flash-ar")
        assertSame(raw, latexForDisplay(raw))
    }

    @Test
    fun textWithoutLatexIsReturnedUntouched() {
        val plain = "Pens cost $5 and $10. **Bold** stays Markdown."
        assertSame(plain, latexForDisplay(plain))
    }

    @Test
    fun displayMathLosesItsDelimitersAndKeepsItsLine() {
        assertEquals(
            "Cost:\n3/7 × 84 = 36\nso 36.",
            latexForDisplay("Cost:\n\\[\n\\frac{3}{7} \\times 84 = 36\n\\]\nso 36.")
        )
        assertEquals("a 12 ÷ 4 b", latexForDisplay("a \\(12 \\div 4\\) b"))
        assertEquals("x 2 · 3 y", latexForDisplay("x $$2 \\cdot 3$$ y"))
    }

    @Test
    fun aCompoundSideOfAFractionIsBracketed() {
        assertEquals("(84 × 3)/7", latexForDisplay("\\(\\frac{84 \\times 3}{7}\\)"))
        assertEquals("(a+b)/(c+d)", latexForDisplay("\\(\\dfrac{a+b}{c+d}\\)"))
        assertEquals("(3 dollars)/(7 pens)", latexForDisplay("\\(\\frac{3\\text{ dollars}}{7\\text{ pens}}\\)"))
    }

    @Test
    fun textKeepsItsWordsAndBoxedBecomesBold() {
        assertEquals("36 dollars", latexForDisplay("\\(36\\text{ dollars}\\)"))
        assertEquals("**36**", latexForDisplay("\\[\\boxed{36}\\]"))
        // A model that already wrote the stars must not get a second pair.
        assertEquals("**36**", latexForDisplay("**\\boxed{36}**"))
        assertEquals("The answer is **36**.", latexForDisplay("The answer is \\boxed{36}."))
    }

    @Test
    fun superscriptsSubscriptsRootsAndDegrees() {
        assertEquals("x² + y₁", latexForDisplay("\\(x^2 + y_{1}\\)"))
        assertEquals("10⁻³", latexForDisplay("\\(10^{-3}\\)"))
        assertEquals("e^(iπ)", latexForDisplay("\\(e^{i\\pi}\\)"))
        assertEquals("√2 and √(x+1) and ∛8", latexForDisplay("\\(\\sqrt{2}\\) and \\(\\sqrt{x+1}\\) and \\(\\sqrt[3]{8}\\)"))
        assertEquals("90°", latexForDisplay("\\(90^\\circ\\)"))
    }

    @Test
    fun alignedRowsBecomeLines() {
        assertEquals(
            "x = 2 + 3\n= 5",
            latexForDisplay("\\[\\begin{aligned} x &= 2 + 3 \\\\ &= 5 \\end{aligned}\\]")
        )
    }

    @Test
    fun anUnknownCommandIsLeftAsWritten() {
        assertEquals("\\overbrace{x}", latexForDisplay("\\(\\overbrace{x}\\)"))
        assertEquals("\\frac{3}{", latexForDisplay("\\frac{3}{"))
    }

    @Test
    fun moneyIsNotMath() {
        val money = "Pens cost $5 and the box \\$10, about 20\\% off."
        assertEquals("Pens cost $5 and the box $10, about 20% off.", latexForDisplay(money))
        assertEquals("half is ½ of $4", latexForDisplay("half is ½ of $4").also { assertFalse(it.contains('\\')) })
    }

    @Test
    fun codeIsNeverTouched() {
        val fenced = "Write it as:\n```latex\n\\frac{3}{7} \\times 84\n```\nwhich gives \\(\\frac{3}{7}\\)."
        assertEquals("Write it as:\n```latex\n\\frac{3}{7} \\times 84\n```\nwhich gives 3/7.", latexForDisplay(fenced))
        assertEquals("type `\\times` for ×", latexForDisplay("type `\\times` for \\(\\times\\)"))
    }

    @Test
    fun aPathKeepsItsBackslashes() {
        val path = "Open C:\\Users\\me\\int\\pi.txt and D:\\times"
        assertEquals(path, latexForDisplay(path))
    }

    @Test
    fun proseMarkdownEscapesSurvive() {
        // `\#` and `\_` are Markdown outside math; only math gives them their LaTeX meaning.
        assertEquals("\\# not a header, snake\\_case", latexForDisplay("\\# not a header, snake\\_case"))
    }
}
