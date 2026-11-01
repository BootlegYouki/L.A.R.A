package org.lara.app.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppLanguageStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getString("language", "en").takeIf { it in setOf("en", "tl") } ?: "en")
    val language = mutable.asStateFlow()
    suspend fun select(language: String) = withContext(Dispatchers.IO) {
        require(language in setOf("en", "tl"))
        check(prefs.edit().putString("language", language).commit())
        mutable.value = language
    }
}
