package org.lara.app.domain.model

object AuthPin {
    const val LENGTH = 6
    fun isValid(value: String): Boolean = value.length == LENGTH && value.all { it in '0'..'9' }
}
