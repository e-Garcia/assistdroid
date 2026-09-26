package ga.egarcia.harness.voice

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class VoiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- Catalog ----------------------------------------------------------------------------------

    @Test
    fun catalogEntriesArePinnedAndComplete() {
        WhisperModels.all.forEach { m ->
            assertEquals("sha256 of ${m.id}", 64, m.sha256.length)
            assertTrue(m.sha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(m.bytes > 10_000_000)
            assertTrue(WhisperModels.url(m).startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/${WhisperModels.REVISION}/"))
            assertEquals(m.englishOnly, m.file.contains(".en-"))
        }
        assertEquals(WhisperModels.all.size, WhisperModels.all.map { it.id }.toSet().size)
        assertEquals(WhisperModels.BASE_EN, WhisperModels.byId("nonsense"))
        assertEquals(WhisperModels.SMALL, WhisperModels.byId("small"))
    }

    @Test
    fun englishOnlyModelsAlwaysUseEnglish() {
        assertEquals("en", VoiceLanguages.effective(WhisperModels.BASE_EN, "es"))
        assertEquals("es", VoiceLanguages.effective(WhisperModels.BASE, "es"))
        assertEquals("auto", VoiceLanguages.effective(WhisperModels.BASE, "klingon"))
    }

    // --- Downloader -------------------------------------------------------------------------------

    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val model get() = WhisperModel("t", "Test", "ggml-test.bin", payload.size.toLong(), sha(payload), true, "")

    /** A tiny HTTP/1.1 server (one request per connection) so downloads are tested end to end without extra libraries. */
    private class MiniServer(private val handler: (path: String, range: Int?) -> Response) {
        class Response(val code: Int, val body: ByteArray = ByteArray(0), val location: String? = null)

        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    Thread { serve(client) }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket) = client.use {
            val reader = it.getInputStream().bufferedReader()
            val path = reader.readLine().split(" ")[1]
            var range: Int? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter("bytes=").removeSuffix("-").trim().toIntOrNull()
            }
            val r = handler(path, range)
            val text = when (r.code) { 200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; else -> "Error" }
            val out = it.getOutputStream()
            out.write(("HTTP/1.1 ${r.code} $text\r\nContent-Length: ${r.body.size}\r\n" + (r.location?.let { l -> "Location: $l\r\n" } ?: "") + "Connection: close\r\n\r\n").toByteArray())
            out.write(r.body)
            out.flush()
        }

        fun stop() = socket.close()
    }

    private lateinit var server: MiniServer
    private val hits = AtomicInteger()
    private var served: ByteArray = payload
    private var honourRange = true
    private val base get() = "http://127.0.0.1:${server.port}/files"

    @Before
    fun startServer() {
        server = MiniServer { path, range ->
            hits.incrementAndGet()
            when {
                path.startsWith("/redirect/") -> MiniServer.Response(302, location = "$base/ggml-test.bin")
                path == "/files/ggml-test.bin" ->
                    if (range != null && honourRange) MiniServer.Response(206, served.copyOfRange(range, served.size)) else MiniServer.Response(200, served)
                else -> MiniServer.Response(404)
            }
        }
    }

    @After
    fun stopServer() = server.stop()

    private fun downloader(url: String = base) = ModelDownloader(url, allowPlainHttp = true, timeoutMs = 5_000)

    @Test
    fun downloadsVerifiesAndPublishesAtomically() = runBlocking {
        val dir = tmp.newFolder()
        val progress = mutableListOf<Long>()
        val file = downloader().download(model, dir) { done, _ -> progress += done }
        assertEquals(sha(payload), sha(file.readBytes()))
        assertFalse("no partial file remains", java.io.File(dir, "ggml-test.bin.part").exists())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun anExistingCompleteFileIsNotDownloadedAgain() = runBlocking {
        val dir = tmp.newFolder()
        downloader().download(model, dir)
        val before = hits.get()
        downloader().download(model, dir)
        assertEquals(before, hits.get())
    }

    @Test
    fun aPartialFileIsResumed() = runBlocking {
        val dir = tmp.newFolder()
        java.io.File(dir, "ggml-test.bin.part").writeBytes(payload.copyOf(100_000))
        val file = downloader().download(model, dir)
        assertEquals(sha(payload), sha(file.readBytes()))
    }

    @Test
    fun aServerThatIgnoresRangeRestartsCleanly() = runBlocking {
        honourRange = false
        val dir = tmp.newFolder()
        java.io.File(dir, "ggml-test.bin.part").writeBytes(payload.copyOf(100_000))
        assertEquals(sha(payload), sha(downloader().download(model, dir).readBytes()))
    }

    @Test
    fun aTamperedFileIsRejectedAndDeleted() = runBlocking {
        served = payload.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val dir = tmp.newFolder()
        try {
            downloader().download(model, dir)
            fail("expected an integrity failure")
        } catch (e: ModelDownloadException) {
            assertTrue(e.message!!, e.message!!.contains("integrity"))
        }
        assertFalse(java.io.File(dir, "ggml-test.bin").exists())
        assertFalse(java.io.File(dir, "ggml-test.bin.part").exists())
    }

    @Test
    fun aTruncatedDownloadIsRejected() = runBlocking {
        served = payload.copyOf(50_000)
        val dir = tmp.newFolder()
        try {
            downloader().download(model, dir)
            fail("expected a failure")
        } catch (e: ModelDownloadException) {
            assertFalse(java.io.File(dir, "ggml-test.bin").exists())
        }
    }

    @Test
    fun redirectsAreFollowed() = runBlocking {
        val dir = tmp.newFolder()
        val file = downloader("http://127.0.0.1:${server.port}/redirect").download(model, dir)
        assertEquals(sha(payload), sha(file.readBytes()))
    }

    @Test
    fun plainHttpIsRefusedUnlessATestAllowsIt() {
        try {
            runBlocking { ModelDownloader(base).download(model, tmp.newFolder()) }
            fail("plain HTTP must be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun aMissingFileGivesAReadableError() = runBlocking {
        try {
            downloader("http://127.0.0.1:${server.port}/nowhere").download(model, tmp.newFolder())
            fail("expected a failure")
        } catch (e: ModelDownloadException) {
            assertTrue(e.message!!, e.message!!.contains("404"))
        }
    }

    // --- Audio helpers ----------------------------------------------------------------------------

    @Test
    fun endpointDetectorEndsAfterSpeechThenSilence() {
        val d = EndpointDetector()
        repeat(3) { assertEquals(EndpointDetector.Verdict.CONTINUE, d.feed(0.002f)) } // room noise
        repeat(10) { assertEquals(EndpointDetector.Verdict.CONTINUE, d.feed(0.2f)) }
        assertTrue(d.heardSpeech)
        var verdict = EndpointDetector.Verdict.CONTINUE
        var quiet = 0
        while (verdict == EndpointDetector.Verdict.CONTINUE && quiet < 30) { verdict = d.feed(0.002f); quiet++ }
        assertEquals(EndpointDetector.Verdict.SPEECH_ENDED, verdict)
        assertEquals("1.2 s of trailing silence at 100 ms chunks", 12, quiet)
    }

    @Test
    fun endpointDetectorGivesUpWhenNobodySpeaks() {
        val d = EndpointDetector()
        var verdict = EndpointDetector.Verdict.CONTINUE
        var n = 0
        while (verdict == EndpointDetector.Verdict.CONTINUE && n < 200) { verdict = d.feed(0.003f); n++ }
        assertEquals(EndpointDetector.Verdict.NO_SPEECH, verdict)
        assertFalse(d.heardSpeech)
        assertEquals(80, n)
    }

    @Test
    fun endpointDetectorStopsAtTheLimit() {
        val d = EndpointDetector(maxMs = 1_000)
        var verdict = EndpointDetector.Verdict.CONTINUE
        repeat(10) { verdict = d.feed(0.3f) }
        assertEquals(EndpointDetector.Verdict.TOO_LONG, verdict)
    }

    @Test
    fun aNoisyRoomDoesNotCountAsSpeech() {
        val d = EndpointDetector()
        var verdict = EndpointDetector.Verdict.CONTINUE
        repeat(100) { if (verdict == EndpointDetector.Verdict.CONTINUE) verdict = d.feed(0.02f) } // constant hum, above the fixed minimum
        assertFalse(d.heardSpeech)
    }

    @Test
    fun pcmHelpers() {
        assertEquals(0f, Pcm.rms(shortArrayOf()), 0f)
        assertEquals(0.5f, Pcm.rms(shortArrayOf(16384, -16384)), 0.001f)
        assertTrue(Pcm.isDigitalSilence(shortArrayOf(0, 0, 0)))
        assertFalse(Pcm.isDigitalSilence(shortArrayOf(0, 1, 0)))
        assertEquals(0.5f, Pcm.toFloat(shortArrayOf(16384))[0], 0.0001f)
    }

    private fun wav(rate: Int, channels: Int, frames: Int, sample: (Int, Int) -> Short): ByteArray {
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) data.putShort(sample(f, c))
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        head.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        head.put("data".toByteArray()).putInt(data.capacity())
        out.write(head.array()); out.write(data.array())
        return out.toByteArray()
    }

    @Test
    fun wavReaderHandlesMonoStereoAndResampling() {
        val mono = Wav.parse(wav(16_000, 1, 1600) { f, _ -> (f * 10).toShort() })
        assertEquals(1600, mono.size)
        assertEquals(10f * 5 / 32768f, mono[5], 1e-6f)
        val stereo = Wav.parse(wav(16_000, 2, 100) { _, c -> if (c == 0) 16384 else 0 })
        assertEquals("channels are averaged", 0.25f, stereo[10], 1e-4f)
        val fromCd = Wav.parse(wav(44_100, 1, 44_100) { _, _ -> 1000 })
        assertEquals("one second stays one second", 16_000, fromCd.size)
        assertEquals(1000 / 32768f, fromCd[8000], 1e-4f)
    }

    @Test
    fun wavReaderRejectsOtherFormats() {
        try { Wav.parse("not a wav file at all, definitely more than forty-four bytes long......".toByteArray()); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun transcriptsLoseSoundAnnotations() {
        assertEquals("", Transcript.clean(" [BLANK_AUDIO]"))
        assertEquals("Open Settings", Transcript.clean(" [ Silence ] Open  Settings (music)"))
        assertEquals("Call later", Transcript.clean("Call *laughs* later"))
        assertEquals("Send it to Ann", Transcript.clean("Send it\n to Ann"))
    }

    @Test
    fun cpuSupportChecksFlagsOnlyOnArm() {
        assertNull(WhisperCpu.unsupportedReason("x86_64", null))
        assertNull(WhisperCpu.unsupportedReason("arm64-v8a", "Features : fp asimd evtstrm fphp asimdhp asimddp"))
        assertTrue(WhisperCpu.unsupportedReason("arm64-v8a", "Features : fp asimd")!!.contains("dot-product"))
        assertTrue(WhisperCpu.unsupportedReason("arm64-v8a", null) != null)
        assertTrue(WhisperCpu.unsupportedReason("armeabi-v7a", "fphp asimddp")!!.contains("64-bit"))
    }
}
