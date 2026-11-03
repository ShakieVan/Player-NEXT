package de.shakie.playernext.update

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * GitHub release notes (Markdown) as readable text for the update screen: headings and
 * **bold** become bold, list items get bullets, hard line breaks inside a paragraph or item are
 * joined (the notes are wrapped at 80 columns), links keep their text. Anything else stays as written.
 */
object ReleaseNotes {
    enum class Kind { HEADING, BULLET, PARAGRAPH }

    /** One paragraph, heading or list item; [bold] are ranges in [text] (end exclusive). */
    data class Block(val kind: Kind, val text: String, val bold: List<IntRange> = emptyList())

    private val heading = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^\s*[-*+]\s+(.*)$""")
    private val numbered = Regex("""^\s*(\d+[.)])\s+(.*)$""")
    private val link = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

    fun parse(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        var kind: Kind? = null
        val current = StringBuilder()
        fun flush() {
            val k = kind ?: return
            blocks += inline(k, current.toString())
            current.clear()
            kind = null
        }
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                line.isBlank() -> flush()
                h != null -> {
                    flush()
                    blocks += inline(Kind.HEADING, h.groupValues[1])
                }
                b != null -> {
                    flush()
                    kind = Kind.BULLET
                    current.append(b.groupValues[1].trim())
                }
                n != null -> {
                    // Numbered items keep their number instead of a bullet.
                    flush()
                    kind = Kind.PARAGRAPH
                    current.append(n.groupValues[1]).append(' ').append(n.groupValues[2].trim())
                }
                else -> {
                    // Continuation of the paragraph or item (a wrapped line).
                    if (kind == null) kind = Kind.PARAGRAPH else current.append(' ')
                    current.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** Links → their text, `code` → code, **bold** and __bold__ → bold ranges. */
    private fun inline(kind: Kind, source: String): Block {
        val plain = link.replace(source) { it.groupValues[1] }.replace("`", "").trim()
        val out = StringBuilder()
        val bold = ArrayList<IntRange>()
        var i = 0
        while (i < plain.length) {
            val marker = plain.regionMatches(i, "**", 0, 2) || plain.regionMatches(i, "__", 0, 2)
            val end = if (marker) plain.indexOf(plain.substring(i, i + 2), i + 2) else -1
            if (marker && end > i + 2) {
                val start = out.length
                out.append(plain, i + 2, end)
                bold += start until out.length
                i = end + 2
            } else {
                out.append(plain[i])
                i++
            }
        }
        return Block(kind, out.toString(), bold)
    }

    /** The blocks as styled text; [gap] is the bullet gap and [radius] the bullet size in pixels. */
    fun format(markdown: String, gap: Int, radius: Int, color: Int): CharSequence {
        val text = SpannableStringBuilder()
        var previous: Kind? = null
        for (block in parse(markdown)) {
            if (previous != null) text.append(if (previous == Kind.BULLET && block.kind == Kind.BULLET) "\n" else "\n\n")
            val start = text.length
            text.append(block.text)
            block.bold.forEach { r -> text.setSpan(StyleSpan(Typeface.BOLD), start + r.first, start + r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            when (block.kind) {
                Kind.HEADING -> {
                    text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(RelativeSizeSpan(1.1f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Kind.BULLET -> text.setSpan(BulletSpan(gap, color, radius), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                Kind.PARAGRAPH -> Unit
            }
            previous = block.kind
        }
        return text
    }
}
