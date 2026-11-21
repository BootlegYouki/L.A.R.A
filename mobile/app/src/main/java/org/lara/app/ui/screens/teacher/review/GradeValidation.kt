package org.lara.app.ui.screens.teacher.review

/** The Hub contract accepts integer points; blank, fractional and out-of-range values cannot be saved. */
internal fun validatedScore(input: String, maximum: Long): Long? {
    if (maximum < 0 || input.isEmpty() || input.any { it !in '0'..'9' }) return null
    return input.toLongOrNull()?.takeIf { it <= maximum }
}
