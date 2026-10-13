package app.brokeometer.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultCoreTest {

    @Test
    fun theMasterPasswordOpensTheVaultAfterABackup() {
        val key = VaultCrypto.newKey()
        val entries = listOf(VaultEntry(title = "Spotify", username = "me", password = "s3cret", note = "line\nbreak"))
        val file = VaultFile.create("correct horse".toCharArray(), key, 1).withEntries(key, entries, 2)
        val text = file.toJson()
        assertFalse("passwords must never be in the file as plain text", text.contains("s3cret"))

        // On a new phone: only the file and the master password.
        val restored = VaultFile.fromJson(text)
        val again = restored.unlock("correct horse".toCharArray())
        assertTrue(again.contentEquals(key))
        assertEquals(entries, restored.readEntries(again))
        assertEquals(2L, restored.updatedAt)
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val key = VaultCrypto.newKey()
        val file = VaultFile.create("right".toCharArray(), key, 1)
        try {
            file.unlock("wrong".toCharArray())
            fail("should not open")
        } catch (e: WrongPassword) {
            // expected
        }
    }

    @Test
    fun changingTheMasterPasswordKeepsThePasswords() {
        val key = VaultCrypto.newKey()
        val entries = listOf(VaultEntry(title = "Wifi", password = "x@123"))
        val file = VaultFile.create("old".toCharArray(), key, 1).withEntries(key, entries, 2).withPassword(key, "new".toCharArray())
        val restored = VaultFile.fromJson(file.toJson())
        assertEquals(entries, restored.readEntries(restored.unlock("new".toCharArray())))
        try {
            restored.unlock("old".toCharArray())
            fail("old password should no longer work")
        } catch (e: WrongPassword) {
            // expected
        }
    }

    @Test
    fun otherFilesAreRejected() {
        try {
            VaultFile.fromJson("{\"format\":\"something-else\"}")
            fail("should not read")
        } catch (e: DamagedVault) {
            // expected
        }
    }
}
