package app.brokeometer.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brokeometer.BrokeApp
import app.brokeometer.vault.VaultEntry
import app.brokeometer.vault.VaultImport
import app.brokeometer.vault.VaultState
import app.brokeometer.vault.WrongPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Passwords page. */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val vault = (application as BrokeApp).container.vault

    val state: StateFlow<VaultState> = vault.state
    val pending: StateFlow<List<VaultEntry>?> = vault.pending

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** The Google export file just imported, so it can be deleted afterwards. */
    private val _exportFile = MutableStateFlow<Uri?>(null)
    val exportFile: StateFlow<Uri?> = _exportFile
    private var importedFrom: Uri? = null
    private var sharedFile = false

    fun canUseFingerprint(): Boolean = vault.canUseFingerprint()

    fun dismissMessage() {
        _message.value = null
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }

    fun create(master: String, again: String) {
        when {
            master.length < MIN_LENGTH -> _message.value = "Make it at least $MIN_LENGTH characters."
            master != again -> _message.value = "The two passwords don't match."
            else -> work {
                runCatching { vault.create(master.toCharArray()) }
                    .onFailure { _message.value = "Could not make the vault: ${it.message}" }
            }
        }
    }

    fun unlock(master: String) = work {
        try {
            vault.unlockWithPassword(master.toCharArray())
        } catch (e: WrongPassword) {
            _message.value = e.message
        } catch (e: Exception) {
            _message.value = e.message ?: "Could not open the vault."
        }
    }

    fun unlockWithFingerprint(activity: FragmentActivity) = prompt(activity, "Unlock passwords") {
        work {
            if (!vault.unlockWithFingerprint()) {
                _message.value = "Use your master password this time. Then you can turn the fingerprint on again."
            }
        }
    }

    fun enableFingerprint(activity: FragmentActivity) = prompt(activity, "Use your fingerprint for passwords") {
        work {
            if (!vault.enableFingerprint()) _message.value = "The fingerprint could not be set up on this phone."
        }
    }

    fun disableFingerprint() = vault.forgetFingerprint()

    fun lock() = vault.lock()

    fun save(entry: VaultEntry) = work { vault.save(entry) }

    fun delete(entry: VaultEntry) = work { vault.delete(entry.id) }

    fun copy(label: String, secret: String) {
        vault.copy(label, secret)
        _message.value = "$label copied. It is wiped from the clipboard in a minute."
    }

    fun changeMaster(master: String, again: String, onDone: () -> Unit) {
        when {
            master.length < MIN_LENGTH -> _message.value = "Make it at least $MIN_LENGTH characters."
            master != again -> _message.value = "The two passwords don't match."
            else -> work {
                vault.changeMaster(master.toCharArray())
                _message.value = "Master password changed."
                onDone()
            }
        }
    }

    // Importing

    fun importNote(text: String) {
        val found = VaultImport.fromNote(text)
        importedFrom = null
        if (found.isEmpty()) {
            _message.value = "No passwords found in that text. Each line needs a name, a colon and a value."
            return
        }
        vault.prepareImport(found)
        if (vault.pending.value.isNullOrEmpty()) {
            vault.cancelImport()
            _message.value = "Everything in that note is already saved."
        }
    }

    val waitingFile: StateFlow<ByteArray?> = vault.waitingFile

    /** A file picked here, from the phone or Google Drive. Drive's own Sheets are fetched as CSV. */
    fun importGoogle(uri: Uri) = work {
        val resolver = getApplication<Application>().contentResolver
        val bytes = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?: runCatching {
                    resolver.openTypedAssetFileDescriptor(uri, "text/csv", null)?.createInputStream()?.use { it.readBytes() }
                }.getOrNull()
        }
        if (bytes == null) {
            _message.value = "Could not open that file. Save it to the phone first (Downloads), then pick it from there."
            return@work
        }
        importBytes(bytes, uri)
    }

    /** A file shared from another app, such as WhatsApp. It can't be deleted from here. */
    fun importWaiting() {
        val bytes = vault.takeFile() ?: return
        work { importBytes(bytes, null) }
    }

    private fun importBytes(bytes: ByteArray, deletable: Uri?) {
        val result = runCatching { VaultImport.fromFile(bytes) }.getOrNull()
        val found = result?.entries.orEmpty()
        if (found.isEmpty()) {
            val columns = result?.columns.orEmpty()
            _message.value = if (columns.isEmpty()) {
                "That file has no passwords in it. Pick the file Google Password Manager exported (it ends in .csv)."
            } else {
                "Couldn't find a password column in that file. It has: ${columns.take(8).joinToString(", ")}."
            }
            return
        }
        importedFrom = deletable
        sharedFile = deletable == null
        vault.prepareImport(found)
        if (vault.pending.value.isNullOrEmpty()) {
            vault.cancelImport()
            deletable?.let { _exportFile.value = it }
            _message.value = "Everything in that file is already saved."
        }
    }

    fun saveImport(chosen: List<VaultEntry>) = work {
        vault.saveImport(chosen)
        _message.value = "Saved ${chosen.size} ${if (chosen.size == 1) "password" else "passwords"}." +
            if (sharedFile) " Now delete the file where it came from (WhatsApp, say): it has every password in plain text." else ""
        importedFrom?.let { _exportFile.value = it }
        importedFrom = null
        sharedFile = false
    }

    fun cancelImport() {
        vault.cancelImport()
        importedFrom?.let { _exportFile.value = it }
        importedFrom = null
    }

    /** The exported file has every password in plain text, so it should not stay on the phone. */
    fun deleteExportFile() = work {
        val uri = _exportFile.value ?: return@work
        val ok = withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }.getOrDefault(false)
        }
        _exportFile.value = null
        _message.value = if (ok) "The exported file is deleted." else "Could not delete it from here. Delete it in your Files app."
    }

    fun keepExportFile() {
        _exportFile.value = null
    }

    private fun prompt(activity: FragmentActivity, title: String, onOk: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _message.value = "The fingerprint lock needs Android 11 or newer. Use your master password."
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Fingerprint or your phone's PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    companion object {
        const val MIN_LENGTH = 8
    }
}
