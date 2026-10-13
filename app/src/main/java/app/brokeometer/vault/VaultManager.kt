package app.brokeometer.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the Passwords page shows. */
sealed interface VaultState {
    /** No vault yet. */
    data object None : VaultState
    data class Locked(val fingerprint: Boolean) : VaultState
    data class Open(val entries: List<VaultEntry>, val fingerprint: Boolean) : VaultState
}

/**
 * The password vault on the phone. The file is in the app's private storage and goes into backups
 * already encrypted; the fingerprint lock lives only on this phone and is never backed up.
 */
class VaultManager(private val context: Context, private val scope: CoroutineScope) {

    private val file = File(context.filesDir, FILE_NAME)
    private val device = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** The vault key while unlocked. Cleared when the vault locks. */
    @Volatile private var key: ByteArray? = null
    private var lockJob: Job? = null

    private val _state = MutableStateFlow<VaultState>(VaultState.None)
    val state: StateFlow<VaultState> = _state

    /** Entries found by an import, waiting for you to check them. */
    private val _pending = MutableStateFlow<List<VaultEntry>?>(null)
    val pending: StateFlow<List<VaultEntry>?> = _pending

    init {
        refreshLocked()
    }

    val exists: Boolean get() = file.exists()
    val fingerprintSet: Boolean get() = device.contains(KEY_DEVICE_WRAP) && hasDeviceKey()

    private fun refreshLocked() {
        _state.value = if (!file.exists()) VaultState.None else VaultState.Locked(fingerprintSet)
    }

    private fun read(): VaultFile = VaultFile.fromJson(file.readText())

    private fun write(vault: VaultFile) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(vault.toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(vault.toJson())
            tmp.delete()
        }
    }

    /** Makes a new, empty vault and opens it. */
    suspend fun create(master: CharArray): Unit = lock.withLock {
        withContext(Dispatchers.Default) {
            check(!file.exists()) { "There is already a vault." }
            val k = VaultCrypto.newKey()
            write(VaultFile.create(master, k, System.currentTimeMillis()))
            key = k
            _state.value = VaultState.Open(emptyList(), fingerprintSet)
        }
    }

    /** Throws [WrongPassword] when it does not match. */
    suspend fun unlockWithPassword(master: CharArray): Unit = lock.withLock {
        withContext(Dispatchers.Default) {
            val vault = read()
            val k = vault.unlock(master)
            key = k
            _state.value = VaultState.Open(sorted(vault.readEntries(k)), fingerprintSet)
        }
    }

    /**
     * Opens with the fingerprint key. Call right after the fingerprint prompt succeeded.
     * False when the key needs the master password instead (for example after a new fingerprint was added).
     */
    suspend fun unlockWithFingerprint(): Boolean = lock.withLock {
        withContext(Dispatchers.Default) {
            val wrapped = device.getString(KEY_DEVICE_WRAP, null) ?: return@withContext false
            val k = try {
                val parts = wrapped.split(':')
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deviceKey() ?: return@withContext false, GCMParameterSpec(128, VaultFile.unb64(parts[0])))
                cipher.doFinal(VaultFile.unb64(parts[1]))
            } catch (e: KeyPermanentlyInvalidatedException) {
                forgetFingerprint()
                return@withContext false
            } catch (e: UserNotAuthenticatedException) {
                return@withContext false
            } catch (e: Exception) {
                return@withContext false
            }
            val vault = read()
            key = k
            _state.value = VaultState.Open(sorted(vault.readEntries(k)), true)
            true
        }
    }

    /** Lets the fingerprint open the vault from now on. Call right after the fingerprint prompt succeeded. */
    suspend fun enableFingerprint(): Boolean = lock.withLock {
        withContext(Dispatchers.Default) {
            val k = key ?: return@withContext false
            try {
                val secret = deviceKey() ?: makeDeviceKey() ?: return@withContext false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secret)
                val data = cipher.doFinal(k)
                device.edit().putString(KEY_DEVICE_WRAP, VaultFile.b64(cipher.iv) + ":" + VaultFile.b64(data)).commit()
                (_state.value as? VaultState.Open)?.let { _state.value = it.copy(fingerprint = true) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun forgetFingerprint() {
        device.edit().remove(KEY_DEVICE_WRAP).commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        when (val s = _state.value) {
            is VaultState.Open -> _state.value = s.copy(fingerprint = false)
            is VaultState.Locked -> _state.value = VaultState.Locked(false)
            else -> Unit
        }
    }

    /** True when this phone has a screen lock, which the fingerprint key needs. */
    fun canUseFingerprint(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        (context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true)

    private fun hasDeviceKey(): Boolean = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    private fun deviceKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    /** A key in Android's secure hardware that only works for 30 seconds after a fingerprint or the phone's PIN. */
    private fun makeDeviceKey(): SecretKey? {
        if (!canUseFingerprint()) return null
        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    }
                }
                .build()
            generator.init(spec)
            generator.generateKey()
        }.getOrNull()
    }

    fun lock() {
        key?.fill(0)
        key = null
        _pending.value = null
        _waitingFile.value = null
        refreshLocked()
    }

    /** Locks a minute after you leave the app. */
    fun appInBackground() {
        if (key == null) return
        lockJob?.cancel()
        lockJob = scope.launch {
            delay(LOCK_AFTER_MS)
            lock()
        }
    }

    fun appInForeground() {
        lockJob?.cancel()
        lockJob = null
    }

    private fun sorted(list: List<VaultEntry>) = list.sortedWith(compareBy({ it.title.lowercase() }, { it.username.lowercase() }))

    private suspend fun change(transform: (List<VaultEntry>) -> List<VaultEntry>): Unit = lock.withLock {
        withContext(Dispatchers.Default) {
            val k = key ?: return@withContext
            val current = (_state.value as? VaultState.Open)?.entries ?: read().readEntries(k)
            val next = sorted(transform(current))
            write(read().withEntries(k, next, System.currentTimeMillis()))
            _state.value = VaultState.Open(next, fingerprintSet)
        }
    }

    suspend fun save(entry: VaultEntry): Unit = change { list ->
        val stamped = entry.copy(updatedAt = System.currentTimeMillis())
        if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) stamped else it } else list + stamped
    }

    suspend fun delete(id: String): Unit = change { list -> list.filter { it.id != id } }

    /** A password file shared from another app, waiting until the vault is open. Cleared when it locks. */
    private val _waitingFile = MutableStateFlow<ByteArray?>(null)
    val waitingFile: StateFlow<ByteArray?> = _waitingFile

    fun offerFile(bytes: ByteArray) {
        _waitingFile.value = bytes
    }

    fun takeFile(): ByteArray? = _waitingFile.value.also { _waitingFile.value = null }

    /** Puts entries found by an import up for review, leaving out any the vault already has. */
    fun prepareImport(found: List<VaultEntry>) {
        val have = (_state.value as? VaultState.Open)?.entries.orEmpty()
        _pending.value = VaultImport.newOnly(found, have)
    }

    fun cancelImport() {
        _pending.value = null
    }

    suspend fun saveImport(chosen: List<VaultEntry>) {
        val now = System.currentTimeMillis()
        change { list -> list + chosen.map { it.copy(updatedAt = now) } }
        _pending.value = null
    }

    /** Needs the vault open (so the old one has already been checked). */
    suspend fun changeMaster(newMaster: CharArray): Unit = lock.withLock {
        withContext(Dispatchers.Default) {
            val k = key ?: return@withContext
            write(read().withPassword(k, newMaster))
        }
    }

    // Backups

    /** The encrypted vault file, for the backup file. Null when there is none. */
    fun backupText(): String? = if (file.exists()) file.readText() else null

    /** Puts a vault from a backup on a phone that has none. False when one is already here. */
    fun restore(text: String): Boolean {
        if (file.exists()) return false
        VaultFile.fromJson(text)
        file.writeText(text)
        refreshLocked()
        return true
    }

    // Copying

    /** Copies a password without it showing in the clipboard preview, and wipes it after a minute. */
    fun copy(label: String, secret: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, secret)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        scope.launch {
            delay(CLIPBOARD_MS)
            withContext(Dispatchers.Main) {
                // Android may not let the app read the clipboard from the background; then clear it anyway.
                val now = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                if (now == null || now == secret) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "vault.json"
        private const val KEY_ALIAS = "vault-fingerprint-key"
        private const val KEY_DEVICE_WRAP = "vault_device_wrap"
        private const val LOCK_AFTER_MS = 60_000L
        private const val CLIPBOARD_MS = 60_000L
    }
}
