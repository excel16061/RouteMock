package com.routemock.app.dashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the device credential encrypted with a key held in the Android Keystore. */
class PairingStore(context: Context) : SyncState {
    private val preferences = context.getSharedPreferences("dashboard_pairing", Context.MODE_PRIVATE)

    var remoteControlEnabled: Boolean
        get() = preferences.getBoolean(KEY_REMOTE, false)
        set(value) = preferences.edit { putBoolean(KEY_REMOTE, value) }

    fun load(): Pairing? {
        val url = preferences.getString(KEY_URL, null) ?: return null
        val label = preferences.getString(KEY_LABEL, null) ?: return null
        val sealed = preferences.getString(KEY_TOKEN, null) ?: return null
        return try {
            Pairing(url, open(sealed), label)
        } catch (_: GeneralSecurityException) {
            clear()
            null
        }
    }

    fun save(pairing: Pairing) = preferences.edit {
        putString(KEY_URL, pairing.baseUrl)
        putString(KEY_LABEL, pairing.label)
        putString(KEY_TOKEN, seal(pairing.token))
    }

    override fun currentPairing(): Pairing? = load()

    /** Remote control always returns to off. Queued revocations are kept. */
    fun clear() = preferences.edit {
        remove(KEY_URL)
        remove(KEY_LABEL)
        remove(KEY_TOKEN)
        remove(KEY_REMOTE)
        remove(KEY_RECEIPT_PENDING)
    }

    /**
     * Keeps a credential only so it can be ended on the server. It stays encrypted and is
     * used for nothing else: no polling, uploads, or commands.
     */
    fun addPendingRevocation(pairing: Pairing) {
        val entry = seal(pairing.token) + SEPARATOR + pairing.baseUrl
        preferences.edit { putStringSet(KEY_REVOCATIONS, entries() + entry) }
    }

    override fun pendingRevocations(): List<Pairing> = entries().mapNotNull { entry ->
        val (sealed, url) = entry.split(SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        try {
            Pairing(url, open(sealed), "")
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override fun clearPendingRevocation(pairing: Pairing) {
        val remaining = entries().filterNot { entry ->
            val (sealed, url) = entry.split(SEPARATOR, limit = 2)
            url == pairing.baseUrl && runCatching { open(sealed) }.getOrNull() == pairing.token
        }.toSet()
        preferences.edit { putStringSet(KEY_REVOCATIONS, remaining) }
    }

    override var receiptUploadPending: Boolean
        get() = preferences.getBoolean(KEY_RECEIPT_PENDING, false)
        set(value) = preferences.edit { putBoolean(KEY_RECEIPT_PENDING, value) }

    private fun entries(): Set<String> = preferences.getStringSet(KEY_REVOCATIONS, emptySet()).orEmpty()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val data = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        }
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "routemock_dashboard"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_URL = "url"
        const val KEY_LABEL = "label"
        const val KEY_TOKEN = "token"
        const val KEY_REMOTE = "remote_control"
        const val KEY_REVOCATIONS = "pending_revocations"
        const val KEY_RECEIPT_PENDING = "receipt_upload_pending"
        const val SEPARATOR = "|"
    }
}
