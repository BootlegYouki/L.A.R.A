package org.lara.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Keep the original mirror in place; other accounts get separate private Room files and cursors. */
class AccountDatabases(context: Context, private val prefix: String = "") {
    init { require(prefix.matches(Regex("[A-Za-z0-9_-]*"))) }
    private val app = context.applicationContext
    private val selecting = Mutex()
    private val caches = ConcurrentHashMap<String, LaraDatabase>()
    private val legacy by lazy { LaraDatabase.create(app, "${prefix}lara.db") }
    suspend fun prepare(userId: String): LaraDatabase = selecting.withLock { withContext(Dispatchers.IO) {
        require(userId.isNotBlank())
        caches[userId] ?: run {
            val owner = legacy.authDao().currentUserId()
            val available = owner == userId || owner == null && legacy.authDao().emptyForClaim() && caches.values.none { it === legacy }
            val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val database = if (available) legacy else LaraDatabase.create(app, "${prefix}account-$hash.db")
            caches[userId] = database
            database
        }
    } }
    fun prepared(userId: String): LaraDatabase = requireNotNull(caches[userId]) { "Account cache not prepared" }
    internal fun close() { (caches.values.toSet() + legacy).forEach { it.close() } }
}
