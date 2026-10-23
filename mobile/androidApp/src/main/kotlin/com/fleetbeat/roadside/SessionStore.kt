package com.fleetbeat.roadside

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fleetbeat.roadside.auth.SavedSession
import com.fleetbeat.roadside.auth.SessionStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "fleetbeat-session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun load(): SavedSession? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val decoded = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            val separator = decoded.indexOf(':')
            SavedSession(decoded.substring(separator + 1), decoded.substring(0, separator).toLong())
        } catch (_: Exception) {
            clear()
            null
        }
    }
    override fun save(session: SavedSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal("${session.expiresAt}:${session.token}".toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
    override fun clear() { check(preferences.edit().clear().commit()) }
}
