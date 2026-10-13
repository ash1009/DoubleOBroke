package app.brokeometer.vault

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One saved login, or a secret on its own such as a Wi-Fi password or an NI number. */
data class VaultEntry(
    val id: String = UUID.randomUUID().toString(),
    /** The site, app or thing it is for. */
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val note: String = "",
    val updatedAt: Long = 0,
) {
    /** Same login, so an import does not add it twice. */
    fun sameAs(other: VaultEntry): Boolean =
        title.trim().equals(other.title.trim(), ignoreCase = true) &&
            username.trim() == other.username.trim() &&
            password == other.password
}

/** Encrypted bytes and the random number they were sealed with. */
class Sealed(val iv: ByteArray, val data: ByteArray)

/**
 * The vault's locks. One random key (the vault key) encrypts the passwords. That key is itself
 * stored twice: locked with the master password (so a backup opens on a new phone) and, on the
 * phone, locked with a key in Android's secure hardware that needs your fingerprint.
 */
object VaultCrypto {
    const val ITERATIONS = 600_000
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun keyFromPassword(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(key: ByteArray, plain: ByteArray): Sealed {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Sealed(iv, cipher.doFinal(plain))
    }

    /** Throws when the key is wrong or the data was changed. */
    fun open(key: ByteArray, sealed: Sealed): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.data)
    }
}

class WrongPassword : Exception("That master password is not right.")
class DamagedVault : Exception("The password file is damaged and could not be read.")

/**
 * The vault as stored in a file (and in backups). Everything secret in it is encrypted; the file
 * on its own is useless without the master password.
 */
data class VaultFile(
    val salt: ByteArray,
    val iterations: Int,
    val keyByPassword: Sealed,
    val entries: Sealed,
    val updatedAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("salt", b64(salt))
        .put("iterations", iterations)
        .put("keyByPassword", sealedJson(keyByPassword))
        .put("entries", sealedJson(entries))
        .put("updatedAt", updatedAt)
        .toString()

    /** The vault key, from the master password. */
    fun unlock(password: CharArray): ByteArray = try {
        VaultCrypto.open(VaultCrypto.keyFromPassword(password, salt, iterations), keyByPassword)
    } catch (e: java.security.GeneralSecurityException) {
        throw WrongPassword()
    }

    fun readEntries(vaultKey: ByteArray): List<VaultEntry> {
        val plain = try {
            VaultCrypto.open(vaultKey, entries)
        } catch (e: java.security.GeneralSecurityException) {
            throw DamagedVault()
        }
        return entriesFromJson(String(plain, Charsets.UTF_8))
    }

    /** Same locks, new contents. */
    fun withEntries(vaultKey: ByteArray, list: List<VaultEntry>, now: Long): VaultFile =
        copy(entries = VaultCrypto.seal(vaultKey, entriesToJson(list).toByteArray(Charsets.UTF_8)), updatedAt = now)

    /** Same contents, new master password. */
    fun withPassword(vaultKey: ByteArray, newPassword: CharArray): VaultFile {
        val newSalt = VaultCrypto.newSalt()
        val wrap = VaultCrypto.seal(VaultCrypto.keyFromPassword(newPassword, newSalt, VaultCrypto.ITERATIONS), vaultKey)
        return copy(salt = newSalt, iterations = VaultCrypto.ITERATIONS, keyByPassword = wrap)
    }

    companion object {
        const val FORMAT = "double-o-broke-vault"

        fun create(password: CharArray, vaultKey: ByteArray, now: Long): VaultFile {
            val salt = VaultCrypto.newSalt()
            val wrap = VaultCrypto.seal(VaultCrypto.keyFromPassword(password, salt, VaultCrypto.ITERATIONS), vaultKey)
            val empty = VaultCrypto.seal(vaultKey, entriesToJson(emptyList()).toByteArray(Charsets.UTF_8))
            return VaultFile(salt, VaultCrypto.ITERATIONS, wrap, empty, now)
        }

        fun fromJson(text: String): VaultFile = try {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) throw DamagedVault()
            VaultFile(
                salt = unb64(o.getString("salt")),
                iterations = o.getInt("iterations"),
                keyByPassword = sealedFrom(o.getJSONObject("keyByPassword")),
                entries = sealedFrom(o.getJSONObject("entries")),
                updatedAt = o.optLong("updatedAt", 0),
            )
        } catch (e: JSONException) {
            throw DamagedVault()
        } catch (e: IllegalArgumentException) {
            throw DamagedVault()
        }

        fun entriesToJson(list: List<VaultEntry>): String = JSONArray().apply {
            list.forEach { e ->
                put(
                    JSONObject().put("id", e.id).put("title", e.title).put("username", e.username)
                        .put("password", e.password).put("url", e.url).put("note", e.note).put("updatedAt", e.updatedAt)
                )
            }
        }.toString()

        fun entriesFromJson(text: String): List<VaultEntry> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VaultEntry(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    title = o.optString("title"),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    url = o.optString("url"),
                    note = o.optString("note"),
                    updatedAt = o.optLong("updatedAt", 0),
                )
            }
        }

        private fun sealedJson(s: Sealed) = JSONObject().put("iv", b64(s.iv)).put("data", b64(s.data))
        private fun sealedFrom(o: JSONObject) = Sealed(unb64(o.getString("iv")), unb64(o.getString("data")))
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
