package com.example.privatenotes.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PROPFIND list parser: pure string in, files out, no server needed. */
class WebDavPropfindParserTest {

    @Test
    fun findsOnlyBackupFilesNewestFirst() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/notes/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/notes/private-notes-20261021-120000.pnb</d:href>
                <d:propstat><d:prop>
                  <d:resourcetype/>
                  <d:getcontentlength>867</d:getcontentlength>
                  <d:getlastmodified>Wed, 21 Oct 2026 06:00:00 GMT</d:getlastmodified>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/notes/private-notes-20261022-090000.json</d:href>
                <d:propstat><d:prop>
                  <d:resourcetype/>
                  <d:getcontentlength>12345</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/notes/desktop.ini</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>400</d:getcontentlength></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val files = parsePropfindFileList(xml)

        assertEquals("the collection itself and non-backup files are skipped", 2, files.size)
        assertEquals("newest name first", "private-notes-20261022-090000.json", files[0].fileName)
        assertEquals(12_345L, files[0].sizeBytes)
        assertEquals("the encrypted file keeps its size and date", "private-notes-20261021-120000.pnb", files[1].fileName)
        assertEquals(867L, files[1].sizeBytes)
                // Wed, 21 Oct 2026 06:00:00 GMT, parsed with RFC_1123_DATE_TIME
        assertEquals(1_792_562_400_000L, files[1].lastModifiedAt)
    }

    @Test
    fun emptyOrUnparsableIsAnEmptyList() {
        assertTrue(parsePropfindFileList("").isEmpty())
        assertTrue(parsePropfindFileList("not xml at all").isEmpty())
        assertTrue(parsePropfindFileList("<d:multistatus></d:multistatus>").isEmpty())
    }

    @Test
    fun handlesNamespacePrefixesAndMissingSize() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/notes/private-notes-20261020-000000.json</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val files = parsePropfindFileList(xml)
        assertEquals(1, files.size)
        assertEquals("a missing size reads as zero, not as an error", 0L, files[0].sizeBytes)
    }

    @Test
    fun handlesADefaultNamespaceWithoutPrefixes() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/dav/notes/</href>
                <propstat><prop><resourcetype><collection/></resourcetype></prop></propstat>
              </response>
              <response>
                <href>/dav/notes/private-notes-20261021-000000.json</href>
                <propstat><prop>
                  <resourcetype/>
                  <getcontentlength>4321</getcontentlength>
                  <getlastmodified>Wed, 21 Oct 2026 00:00:00 GMT</getlastmodified>
                </prop></propstat>
              </response>
              <responsedescription>shared folder</responsedescription>
            </multistatus>
        """.trimIndent()

        val files = parsePropfindFileList(xml)

        assertEquals(1, files.size)
        assertEquals("private-notes-20261021-000000.json", files[0].fileName)
        assertEquals(4321L, files[0].sizeBytes)
    }
}
