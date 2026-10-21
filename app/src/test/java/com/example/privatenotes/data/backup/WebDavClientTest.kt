package com.example.privatenotes.data.backup

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()

        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        http = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `connection test creates a missing collection and uses basic auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))

        client().testConnection()

        val first = server.takeRequest()
        assertEquals("PROPFIND", first.method)
        assertEquals("/dav/notes/", first.path)
        assertEquals(Credentials.basic("user", "pass", Charsets.UTF_8), first.getHeader("Authorization"))

        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/dav/notes/", mkcol.path)

        val second = server.takeRequest()
        assertEquals("PROPFIND", second.method)
        assertEquals("/dav/notes/", second.path)
    }

    @Test
    fun `upload and read-back verification use one file`() = runBlocking {
        val bytes = """{"format":"private-notes-backup"}""".toByteArray()
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))

        val client = client()
        val upload = client.upload("backup.json", bytes, "application/json".toMediaType())
        val verification = client.verify("backup.json", bytes)

        assertEquals("backup.json", upload.remoteFileName)
        assertEquals(WebDavVerification.FULL, verification)
        assertEquals("PROPFIND", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/dav/notes/backup.json", put.path)
        assertEquals("application/json", put.getHeader("Content-Type"))
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/dav/notes/backup.json", get.path)
    }

    @Test
    fun `a wrong password is reported as unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = assertThrows(BackupException::class.java) {
            runBlocking { client().testConnection() }
        }

        assertEquals(BackupErrorKind.UNAUTHORIZED, failure.kind)
    }

    @Test
    fun `redirects are refused instead of forwarding credentials`() {
        server.enqueue(MockResponse().setResponseCode(301))

        val failure = assertThrows(BackupException::class.java) {
            runBlocking { client().testConnection() }
        }

        assertEquals(BackupErrorKind.SERVER, failure.kind)
        assertTrue(failure.message.orEmpty().contains("redirect", ignoreCase = true))
    }

    @Test
    fun `a read-back mismatch is a storage failure`() {
        val bytes = "expected".toByteArray()
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).setBody("different"))

        val client = client()
        runBlocking { client.upload("backup.json", bytes, "application/json".toMediaType()) }

        val failure = assertThrows(BackupException::class.java) {
            runBlocking { client.verify("backup.json", bytes) }
        }

        assertEquals(BackupErrorKind.STORAGE, failure.kind)
    }

    @Test
    fun `a server that refuses GET can verify by content length`() = runBlocking {
        val bytes = "expected".toByteArray()
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop>
                |<d:getcontentlength>${bytes.size}</d:getcontentlength>
                |</d:prop></d:propstat></d:response></d:multistatus>""".trimMargin(),
            ),
        )

        val client = client()
        client.upload("backup.json", bytes, "application/json".toMediaType())
        val verification = client.verify("backup.json", bytes)

        assertEquals(WebDavVerification.LENGTH_ONLY, verification)
    }

    private fun client(): WebDavClient = WebDavClient(
        http = http,
        settings = WebDavSettings(
            baseUrl = server.url("/dav/").toString(),
            username = "user",
            remoteDirectory = "notes",
            hasPassword = true,
        ),
        password = "pass",
    )

    private fun multistatus(): String =
        """<?xml version="1.0" encoding="utf-8"?>
        |<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/notes/</d:href>
        |<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
        |</d:propstat></d:response></d:multistatus>""".trimMargin()
}
