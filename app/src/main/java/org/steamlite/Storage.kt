package org.steamlite

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("steam-session", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("steam-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("data", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }
    fun read(): String? = runCatching {
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP))) }
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }.getOrNull()
    fun clear() { prefs.edit().clear().apply() }
}
@Entity data class Message(@PrimaryKey val id: String, val owner: String, val peer: Long, val text: String, val time: Long, val outgoing: Boolean, val status: String)
@Entity data class CachedResponse(@PrimaryKey val url: String, val body: String, val time: Long)
@Dao interface CacheDao {
    // SQLite selects the other columns from the row containing MAX(time).
    // Ties may select either message, but always return a single row per peer.
    @Query("SELECT id, owner, peer, text, MAX(time) AS time, outgoing, status FROM Message WHERE owner = :owner GROUP BY peer ORDER BY time DESC") fun conversations(owner: String): Flow<List<Message>>
    @Query("SELECT * FROM (SELECT * FROM Message WHERE owner = :owner AND peer = :peer ORDER BY time DESC LIMIT :limit) ORDER BY time ASC") fun messages(owner: String, peer: Long, limit: Int = 500): Flow<List<Message>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(message: Message)
    @Query("SELECT text FROM Message WHERE id = :id") suspend fun messageText(id: String): String?
    @Transaction suspend fun putFromSteam(message: Message) {
        val current = messageText(message.id)
        val text = if (current == null) message.text else ChatContent.confirmedText(current, message.text)
        if (text.isNotBlank()) put(message.copy(text = text))
    }
    @Query("DELETE FROM Message WHERE id = :id") suspend fun deleteMessage(id: String)
    /** Older builds persisted sticker rows as blank text; those show up as empty bubbles. */
    @Query("DELETE FROM Message WHERE text = ''") suspend fun deleteEmptyMessages()
    @Query("SELECT * FROM CachedResponse WHERE url = :url") suspend fun cached(url: String): CachedResponse?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(value: CachedResponse)
    @Query("DELETE FROM Message") suspend fun clearMessages()
}
@Database(entities = [Message::class, CachedResponse::class], version = 1, exportSchema = false)
abstract class LiteDatabase : RoomDatabase() { abstract fun dao(): CacheDao }
