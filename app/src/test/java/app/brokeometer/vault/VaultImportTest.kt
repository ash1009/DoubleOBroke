package app.brokeometer.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultImportTest {

    private val note = """
        National Insurance Number UK: QQ123456C

        My anime list: Anime_fan_42
                       : animepass

        My drama list: dramapass

        Hackerrank: hackpass

        Home wifi password: wifi@123

        Spotify: m3k9q2x7v1c8z4b6n5
                  : spotpass

        Yahoo: alex.example1
                   : yahoo@one

                   : alex.example2
                   : yahoo@two
                   : 4321 (Passkey)
        https://example.com/login: someone
            : webpass
    """.trimIndent()

    @Test
    fun readsTheNoteTheWayItIsWritten() {
        val got = VaultImport.fromNote(note).map { listOf(it.title, it.username, it.password, it.note, it.url) }
        assertEquals(
            listOf(
                listOf("National Insurance Number UK", "", "QQ123456C", "", ""),
                listOf("My anime list", "Anime_fan_42", "animepass", "", ""),
                listOf("My drama list", "", "dramapass", "", ""),
                listOf("Hackerrank", "", "hackpass", "", ""),
                listOf("Home wifi password", "", "wifi@123", "", ""),
                listOf("Spotify", "m3k9q2x7v1c8z4b6n5", "spotpass", "", ""),
                listOf("Yahoo", "alex.example1", "yahoo@one", "", ""),
                listOf("Yahoo", "alex.example2", "yahoo@two", "4321 (Passkey)", ""),
                listOf("https://example.com/login", "someone", "webpass", "", "https://example.com/login"),
            ),
            got,
        )
    }

    @Test
    fun readsGooglePasswordManagerExport() {
        val csv = "name,url,username,password,note\n" +
            "accounts.google.com,https://accounts.google.com/,me@gmail.com,\"p,ss\"\"word\",\n" +
            ",https://www.netflix.com/login,me@x.com,nf123,\"line one\nline two\"\n" +
            "android://abc@com.spotify.music/,android://abc@com.spotify.music/,user,sp1,\n"
        val got = VaultImport.fromGoogleCsv(csv)
        assertEquals(3, got.size)
        assertEquals(listOf("accounts.google.com", "me@gmail.com", "p,ss\"word"), listOf(got[0].title, got[0].username, got[0].password))
        assertEquals("netflix.com", got[1].title)
        assertEquals("line one\nline two", got[1].note)
        assertEquals("sp1", got[2].password)
    }

    @Test
    fun importsSkipWhatIsAlreadyThere() {
        val have = listOf(VaultEntry(title = "Spotify", username = "u", password = "p"))
        val found = listOf(VaultEntry(title = "spotify", username = "u", password = "p"), VaultEntry(title = "Hack", password = "x"), VaultEntry(title = "Hack", password = "x"))
        assertEquals(listOf("Hack"), VaultImport.newOnly(found, have).map { it.title })
    }

    @Test
    fun readsTheExportSavedWithTabs() {
        val tsv = "name\turl\tusername\tpassword\tnote\nnetflix.com\thttps://netflix.com\tme@x.com\tnf,123\t\n"
        val r = VaultImport.fromFile(tsv.toByteArray())
        assertEquals(1, r.entries.size)
        assertEquals("nf,123", r.entries[0].password)
    }

    @Test
    fun readsTheExportSavedAsASpreadsheet() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
            put(
                "xl/sharedStrings.xml",
                "<sst><si><t>name</t></si><si><t>url</t></si><si><t>username</t></si><si><t>password</t></si>" +
                    "<si><t>spotify.com</t></si><si><t>me</t></si><si><r><t>sp</t></r><r><t>1</t></r></si></sst>",
            )
            put(
                "xl/worksheets/sheet1.xml",
                "<worksheet><sheetData>" +
                    "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>" +
                    "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>4</v></c><c r=\"C2\" t=\"s\"><v>5</v></c><c r=\"D2\" t=\"s\"><v>6</v></c></row>" +
                    "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>wifi</t></is></c><c r=\"D3\"><v>12345</v></c></row>" +
                    "</sheetData></worksheet>",
            )
        }
        val r = VaultImport.fromFile(out.toByteArray())
        assertEquals(listOf("name", "url", "username", "password"), r.columns)
        assertEquals(listOf("spotify.com", "me", "sp1"), listOf(r.entries[0].title, r.entries[0].username, r.entries[0].password))
        assertEquals(listOf("wifi", "", "12345"), listOf(r.entries[1].title, r.entries[1].username, r.entries[1].password))
    }
}
