package org.lara.app.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionTokenStore {
    fun load(): StoredToken?
    fun save(value: StoredToken)
    fun clear()
}

data class StoredToken(val token: String, val userId: String, val expiresAt: Long, val hub: String) {
    override fun toString() = "StoredToken([redacted])"
}

/** Only ciphertext and a random IV are written to disk; PINs are never accepted here. */
class TokenStore(context: Context) : SessionTokenStore {
    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("lara_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("lara_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun save(value: StoredToken) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = org.json.JSONObject().put("token", value.token).put("user", value.userId)
            .put("expiry", value.expiresAt).put("hub", value.hub).toString().toByteArray(Charsets.UTF_8)
        val data = Base64.encodeToString(cipher.doFinal(payload), Base64.NO_WRAP)
        check(prefs.edit().putString("data", data).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    override fun load(): StoredToken? {
        val data = prefs.getString("data", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val value = org.json.JSONObject(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8))
        return StoredToken(value.getString("token"), value.getString("user"), value.getLong("expiry"), value.getString("hub"))
    }
    override fun clear() { check(prefs.edit().clear().commit()) }
}
