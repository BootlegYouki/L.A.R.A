package org.lara.app.ui.screens.enrollment

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import java.util.Locale
import org.lara.app.domain.enrollment.ClassCode

/** Format only the display: rewriting an IME edit can drop or reorder fast keystrokes. */
internal object GroupedClassCode : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val start = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
        val end = (raw.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(start)
        fun cleanPrefix(offset: Int) = raw.substring(start.coerceAtMost(offset), offset.coerceAtMost(end))
            .replace("-", "").uppercase(Locale.ROOT).length
        val positions = IntArray(raw.length + 1) { cleanPrefix(it) }
        val clean = raw.substring(start, end).replace("-", "").uppercase(Locale.ROOT)
        val display = ClassCode.display(clean)
        return TransformedText(AnnotatedString(display), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val at = positions[offset.coerceIn(0, raw.length)]
                return (if (clean.length > 3 && at > 3) at + 1 else at).coerceIn(0, display.length)
            }
            override fun transformedToOriginal(offset: Int): Int {
                val at = (if (clean.length > 3 && offset > 3) offset - 1 else offset).coerceIn(0, clean.length)
                return positions.indexOfLast { it <= at }.coerceAtLeast(0)
            }
        })
    }
}
