package org.lara.app.domain.enrollment

import java.util.Locale

object ClassCode {
    private val valid = Regex("[A-HJ-NP-Z2-9]{6}")

    fun canonicalOrNull(input: String): String? =
        input.trim().replace("-", "").uppercase(Locale.ROOT).takeIf(valid::matches)

    fun draft(input: String): String = input.trim().replace("-", "").uppercase(Locale.ROOT).take(6)

    fun display(draft: String): String =
        if (draft.length > 3) draft.take(3) + "-" + draft.drop(3) else draft
}
