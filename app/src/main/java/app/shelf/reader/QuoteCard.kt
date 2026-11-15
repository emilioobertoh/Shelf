package app.shelf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Tidies a selection into a quote and holds the length limit. */
object QuoteText {
    /** About a short paragraph. The card shrinks the font for long quotes, down to a readable minimum. */
    const val MAX_CHARS = 400

    private val QUOTE_MARKS = "\"'“”‘’«»„"

    fun clean(raw: String): String {
        var text = raw.replace(Regex("\\s+"), " ").trim().trim { it in QUOTE_MARKS }.trim()
        if (text.isEmpty()) return text
        // Mark a quote cut mid-sentence with an ellipsis on that side.
        if (text.first().isLowerCase()) text = "…$text"
        if (text.last().isLetterOrDigit() || text.last() in ",;:") text = text.trimEnd(',', ';', ':') + "…"
        return text
    }
}

/** Colours for one Story, taken from the book cover. */
data class StoryColors(
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val card: Int,
    val text: Int,
    val secondaryText: Int,
    val accent: Int,
)

enum class CardStyle { Light, Dark }

object StoryPalette {
    private const val FALLBACK = 0xFFE8772E.toInt() // Shelf's accent, for books without a cover
    private const val INK = 0xFF1F1A17.toInt()
    private const val PAPER = 0xFFFBF8F3.toInt()

    fun from(cover: Bitmap?, style: CardStyle): StoryColors {
        val palette = cover?.let { Palette.from(it).maximumColorCount(24).generate() }
        val vivid = palette?.let { it.vibrantSwatch ?: it.mutedSwatch ?: it.dominantSwatch }?.rgb ?: FALLBACK
        val deep = palette?.let { it.darkMutedSwatch ?: it.darkVibrantSwatch }?.rgb
            ?: ColorUtils.blendARGB(vivid, Color.BLACK, 0.6f)
        val soft = palette?.let { it.lightMutedSwatch ?: it.lightVibrantSwatch }?.rgb
            ?: ColorUtils.blendARGB(vivid, Color.WHITE, 0.85f)

        val card = when (style) {
            // A whisper of the cover colour on paper white, so the card belongs to the background.
            CardStyle.Light -> ColorUtils.blendARGB(PAPER, soft, 0.25f)
            CardStyle.Dark -> ColorUtils.blendARGB(deep, Color.BLACK, 0.35f)
        }
        val text = if (ColorUtils.calculateContrast(INK, card) >= 7.0) INK else Color.WHITE
        val accent = listOf(vivid, deep, soft).firstOrNull { ColorUtils.calculateContrast(it, card) >= 3.0 } ?: text
        return StoryColors(
            backgroundTop = ColorUtils.blendARGB(deep, Color.BLACK, 0.15f),
            backgroundBottom = vivid,
            card = card,
            text = text,
            secondaryText = ColorUtils.setAlphaComponent(text, 0xA8),
            accent = accent,
        )
    }
}

/** Draws a 1080×1920 Instagram Story: cover-coloured gradient, a card with the quote, title and author. */
class QuoteCardRenderer(context: Context) {
    private val serif = Typeface.createFromAsset(context.assets, "fonts/XCharter-Roman.otf")
    private val serifBold = Typeface.createFromAsset(context.assets, "fonts/XCharter-Bold.otf")
    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)

    fun render(quote: String, title: String, author: String, cover: Bitmap?, colors: StoryColors): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background gradient.
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, WIDTH * 0.3f, HEIGHT.toFloat(),
                colors.backgroundTop, colors.backgroundBottom, Shader.TileMode.CLAMP,
            )
        })

        val cardWidth = WIDTH - 2 * CARD_SIDE
        val innerWidth = cardWidth - 2 * PAD

        // Footer: cover thumbnail + title / author.
        val thumbW = if (cover != null) 132 else 0
        val thumbH = if (cover != null) 198 else 0
        val footerTextWidth = innerWidth - (if (cover != null) thumbW + 36 else 0)
        val titleLayout = layout(title, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sansBold; textSize = 40f; color = colors.text
        }, footerTextWidth, maxLines = 2, spacing = 1.15f)
        val authorLayout = layout(author, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sans; textSize = 34f; color = colors.secondaryText
        }, footerTextWidth, maxLines = 2, spacing = 1.15f)
        val footerTextHeight = titleLayout.height + 10 + authorLayout.height
        val footerHeight = maxOf(thumbH, footerTextHeight)

        // Quote: start large and step down until it fits the card's maximum height.
        val markSize = 150f
        val markHeight = 96
        val fixed = PAD + markHeight + GAP + GAP + DIVIDER + GAP + footerHeight + PAD
        val maxQuoteHeight = MAX_CARD_HEIGHT - fixed
        var size = 72f
        var quoteLayout: StaticLayout
        while (true) {
            quoteLayout = layout(quote, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif; textSize = size; color = colors.text
            }, innerWidth, maxLines = Int.MAX_VALUE, spacing = 1.32f)
            if (quoteLayout.height <= maxQuoteHeight || size <= MIN_TEXT) break
            size -= 2f
        }

        val cardHeight = fixed + quoteLayout.height
        val top = ((HEIGHT - cardHeight) / 2f).coerceAtLeast(SAFE_TOP.toFloat())
        val card = RectF(CARD_SIDE.toFloat(), top, (WIDTH - CARD_SIDE).toFloat(), top + cardHeight)

        // Soft shadow, then the card.
        canvas.drawRoundRect(RectF(card).apply { offset(0f, 18f) }, RADIUS, RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; alpha = 50
            maskFilter = android.graphics.BlurMaskFilter(40f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        canvas.drawRoundRect(card, RADIUS, RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card })

        val left = card.left + PAD
        var y = card.top + PAD

        // Opening quote mark.
        canvas.drawText("“", left - 6f, y + markSize * 0.72f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serifBold; textSize = markSize; color = colors.accent
        })
        y += markHeight + GAP

        canvas.save(); canvas.translate(left, y); quoteLayout.draw(canvas); canvas.restore()
        y += quoteLayout.height + GAP

        canvas.drawRect(left, y, left + 96f, y + DIVIDER, Paint().apply { color = colors.accent })
        y += DIVIDER + GAP

        var textLeft = left
        if (cover != null) {
            drawCover(canvas, cover, RectF(left, y, left + thumbW, y + thumbH))
            textLeft = left + thumbW + 36
        }
        val textTop = y + (footerHeight - footerTextHeight) / 2f
        canvas.save(); canvas.translate(textLeft, textTop); titleLayout.draw(canvas); canvas.restore()
        canvas.save(); canvas.translate(textLeft, textTop + titleLayout.height + 10); authorLayout.draw(canvas); canvas.restore()

        return bitmap
    }

    private fun drawCover(canvas: Canvas, cover: Bitmap, rect: RectF) {
        val scale = maxOf(rect.width() / cover.width, rect.height() / cover.height)
        val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    rect.left + (rect.width() - cover.width * scale) / 2,
                    rect.top + (rect.height() - cover.height * scale) / 2,
                )
            })
        }
        canvas.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        /** Wide side margins: Instagram zooms a 9:16 image to fill taller phones and crops the sides. */
        private const val CARD_SIDE = 136
        private const val PAD = 72
        private const val GAP = 40
        private const val DIVIDER = 6
        private const val RADIUS = 44f
        private const val MIN_TEXT = 40f
        /** Instagram draws its own buttons over the top and bottom ~230 px of a Story. */
        private const val SAFE_TOP = 236
        private const val MAX_CARD_HEIGHT = HEIGHT - 2 * SAFE_TOP
    }
}
