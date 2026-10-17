package ga.egarcia.harness.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloadException(message: String) : Exception(message)

/**
 * Downloads one model over HTTPS into [dir], resuming a partial file, and accepts it only if size and SHA-256
 * match the catalog. The finished file appears atomically (renamed from `.part`), so a half-downloaded model can
 * never be loaded. [allowPlainHttp] exists for tests against a local server; the app never sets it.
 */
class ModelDownloader(
    private val baseUrl: String = WhisperModels.BASE_URL,
    private val allowPlainHttp: Boolean = false,
    private val timeoutMs: Int = 20_000,
) {
    suspend fun download(model: WhisperModel, dir: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, model.file)
            if (target.isFile && target.length() == model.bytes) return@withContext target
            val part = File(dir, model.file + ".part")
            if (part.length() > model.bytes) part.delete()

            try {
                fetch(model, part, onProgress)
            } catch (e: IOException) {
                throw ModelDownloadException("Download interrupted (${e.message ?: e.javaClass.simpleName}). Try again to resume.")
            }

            if (part.length() != model.bytes) {
                part.delete()
                throw ModelDownloadException("The downloaded file has the wrong size. It was deleted; try again.")
            }
            if (sha256(part) != model.sha256) {
                part.delete()
                throw ModelDownloadException("The downloaded file failed its integrity check (SHA-256) and was deleted.")
            }
            if (!part.renameTo(target)) throw ModelDownloadException("Could not save the model file.")
            target
        }

    private suspend fun fetch(model: WhisperModel, part: File, onProgress: (Long, Long) -> Unit) {
        var have = part.length()
        if (have == model.bytes) return
        var url = WhisperModels.url(model, baseUrl)
        var connection: HttpURLConnection? = null
        repeat(MAX_REDIRECTS + 1) {
            connection?.disconnect()
            check(url.startsWith("https://") || (allowPlainHttp && url.startsWith("http://"))) { "Refusing a non-HTTPS download address" }
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = connection!!.responseCode
            if (code in 300..399) {
                url = connection!!.getHeaderField("Location") ?: throw ModelDownloadException("Redirect without a location")
                return@repeat
            }
            val c = connection!!
            try {
                when (code) {
                    200 -> { part.delete(); have = 0 } // the server ignored Range: start over
                    206 -> Unit
                    416 -> return // the partial file is already complete; verification decides
                    else -> throw ModelDownloadException("The download server answered HTTP $code.")
                }
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have)
                    val buffer = ByteArray(64 * 1024)
                    c.inputStream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            have += n
                            onProgress(have, model.bytes)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            return
        }
        throw ModelDownloadException("Too many redirects")
    }

    companion object {
        private const val MAX_REDIRECTS = 5

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
