package dev.mixinmcp.mcsources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit

class NeoFormRuntimeException(
    message: String,
    val stdout: String = "",
    val stderr: String = "",
) : RuntimeException(message)

class NeoFormRuntimeRunner(
    private val toolsCacheRoot: Path = defaultToolsCacheRoot,
    private val timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun run(
        nfrtHomeDir: Path,
        nfrtArgs: List<String>,
        onOutputLine: (String) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val toolJar = ensureToolJar()
        Files.createDirectories(nfrtHomeDir)

        val command = buildList {
            add(resolveJavaExecutable())
            add("-jar")
            add(toolJar.toAbsolutePath().toString())
            add("--home-dir")
            add(nfrtHomeDir.toAbsolutePath().toString())
            add("--no-color")
            add("--no-emojis")
            addAll(nfrtArgs)
        }

        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw NeoFormRuntimeException("Failed to launch neoform-runtime subprocess: ${e.message}")
        }

        val stdoutTail = OutputTail()
        val stderrTail = OutputTail()
        val stdoutThread = pumpLines(process.inputStream, stdoutTail, onOutputLine, "mixinmcp-nfrt-stdout")
        val stderrThread = pumpLines(process.errorStream, stderrTail, onOutputLine, "mixinmcp-nfrt-stderr")

        val finished = try {
            // A blocking waitFor never sees coroutine cancellation; runInterruptible turns it into an
            // interrupt so a cancelled tool call stops NFRT instead of running it out to the timeout.
            runInterruptible { process.waitFor(timeoutMinutes, TimeUnit.MINUTES) }
        } catch (e: CancellationException) {
            destroyProcessTree(process)
            throw e
        }
        if (!finished) {
            destroyProcessTree(process)
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            throw NeoFormRuntimeException(
                "neoform-runtime timed out after $timeoutMinutes minutes.",
                stdout = sanitizeAndTail(stdoutTail.snapshot()),
                stderr = sanitizeAndTail(stderrTail.snapshot()),
            )
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val stdout = sanitizeAndTail(stdoutTail.snapshot())
            val stderr = sanitizeAndTail(stderrTail.snapshot())
            val detail = buildString {
                append("neoform-runtime exited with code $exitCode.")
                if (stderr.isNotBlank()) append("\n\nstderr (tail):\n").append(stderr)
                if (stdout.isNotBlank()) append("\n\nstdout (tail):\n").append(stdout)
            }
            throw NeoFormRuntimeException(detail, stdout = stdout, stderr = stderr)
        }
    }

    private class OutputTail {
        private val text = StringBuilder()

        @Synchronized
        fun appendLine(line: String) {
            text.append(line).append('\n')
            // Only the tail is ever reported, so cap memory on a chatty run.
            if (text.length > MAX_BUFFERED_CHARS) text.delete(0, text.length - MAX_BUFFERED_CHARS / 2)
        }

        @Synchronized
        fun snapshot(): String = text.toString()
    }

    private fun pumpLines(stream: InputStream, sink: OutputTail, onLine: (String) -> Unit, threadName: String): Thread =
        Thread({
            runCatching {
                stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        sink.appendLine(line)
                        runCatching { onLine(line) }
                    }
                }
            }
        }, threadName).apply { isDaemon = true; start() }

    // NFRT runs decompilers and other NeoForm steps as child JVMs, which survive when only the
    // parent is destroyed (always on Windows, which has no process group to signal).
    private fun destroyProcessTree(process: Process) {
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        process.destroyForcibly()
        descendants.forEach { runCatching { it.destroyForcibly() } }
    }

    private val toolDownloadLock = Any()

    private fun ensureToolJar(): Path {
        val jarName = "$ARTIFACT_NAME-$NEOFORM_RUNTIME_VERSION-$ARTIFACT_CLASSIFIER.jar"
        val groupPath = ARTIFACT_GROUP.replace('.', '/')
        val localPath = toolsCacheRoot
            .resolve(groupPath)
            .resolve(ARTIFACT_NAME)
            .resolve(NEOFORM_RUNTIME_VERSION)
            .resolve(jarName)

        if (Files.isRegularFile(localPath) && Files.size(localPath) > 0L) {
            return localPath
        }

        return synchronized(toolDownloadLock) {
            if (Files.isRegularFile(localPath) && Files.size(localPath) > 0L) {
                return@synchronized localPath
            }
            downloadToolJar(localPath, "$NEOFORGED_MAVEN$groupPath/$ARTIFACT_NAME/$NEOFORM_RUNTIME_VERSION/$jarName", jarName)
        }
    }

    private fun downloadToolJar(localPath: Path, url: String, jarName: String): Path {
        Files.createDirectories(localPath.parent)
        val tmp = Files.createTempFile(localPath.parent, "$jarName.", ".tmp")
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                throw IOException("HTTP ${response.statusCode()} downloading $url")
            }
            response.body().use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp, localPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, localPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw NeoFormRuntimeException("Failed to download neoform-runtime from $url: ${e.message}")
        }
        return localPath
    }

    companion object {
        const val NEOFORM_RUNTIME_VERSION = "2.0.31"
        private const val ARTIFACT_GROUP = "net.neoforged"
        private const val ARTIFACT_NAME = "neoform-runtime"
        private const val ARTIFACT_CLASSIFIER = "all"
        private const val NEOFORGED_MAVEN = "https://maven.neoforged.net/releases/"
        private const val USER_AGENT = "MixinMCP (+https://github.com/muon-rw/MixinMCP)"
        private const val DEFAULT_TIMEOUT_MINUTES = 10L
        private const val MAX_OUTPUT_CHARS = 3500
        private const val MAX_BUFFERED_CHARS = 256 * 1024

        val defaultToolsCacheRoot: Path
            get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "tools")

        internal fun resolveJavaExecutable(): String {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val exeName = if (isWindows) "java.exe" else "java"

            val javaHomeProp = System.getProperty("java.home").orEmpty()
            if (javaHomeProp.isNotEmpty()) {
                val candidate = Paths.get(javaHomeProp, "bin", exeName)
                if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString()
            }
            val javaHomeEnv = System.getenv("JAVA_HOME").orEmpty()
            if (javaHomeEnv.isNotEmpty()) {
                val candidate = Paths.get(javaHomeEnv, "bin", exeName)
                if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString()
            }
            return "java"
        }

        internal fun sanitizeAndTail(raw: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
            val cleaned = stripAnsiAndControlChars(raw).trim()
            if (cleaned.length <= maxChars) return cleaned
            return "... (truncated)\n" + cleaned.takeLast(maxChars)
        }

        internal fun stripAnsiAndControlChars(text: String): String {
            if (text.isEmpty()) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\u001B') {
                    i = skipEscapeSequence(text, i)
                    continue
                }
                if (c == '\n' || c == '\r' || c == '\t' || (c.code >= 0x20 && c.code !in 0x7F..0x9F)) {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private fun skipEscapeSequence(text: String, start: Int): Int {
            var i = start + 1
            if (i >= text.length) return i
            return when (text[i]) {
                '[' -> {
                    i++
                    while (i < text.length && text[i].code in 0x20..0x3F) i++
                    if (i < text.length) i++
                    i
                }
                ']' -> {
                    i++
                    while (i < text.length && text[i] != '\u0007' &&
                        !(text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\')
                    ) {
                        i++
                    }
                    when {
                        i < text.length && text[i] == '\u0007' -> i + 1
                        i < text.length && text[i] == '\u001B' -> i + 2
                        else -> i
                    }
                }
                in '@'..'_' -> i + 1
                else -> i
            }
        }
    }
}
