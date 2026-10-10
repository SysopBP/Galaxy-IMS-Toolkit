package dev.bluehouse.enablevolte

import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

data class RootResult(val exitCode: Int, val output: String, val timedOut: Boolean, val truncated: Boolean) {
    fun requireSuccess(): String {
        check(!timedOut) { "Root command timed out" }
        check(exitCode == 0) { "Root command failed ($exitCode): ${output.take(300)}" }
        check(!truncated) { "Root output exceeds the inspection limit" }
        return output
    }
}

object RootCommands {
    fun run(command: String, timeoutSeconds: Long = 15, maxBytes: Int = 2 * 1024 * 1024): RootResult {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit<Pair<String, Boolean>> {
            val bytes = java.io.ByteArrayOutputStream()
            var truncated = false
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val available = maxBytes - bytes.size()
                    if (count > available) truncated = true
                    if (available > 0) bytes.write(buffer, 0, minOf(count, available))
                }
            }
            bytes.toString("UTF-8") to truncated
        }
        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val result = try { task.get(2, TimeUnit.SECONDS) } catch (_: Exception) { "" to false }
            return RootResult(if (finished) process.exitValue() else -1, result.first, !finished, result.second)
        } finally {
            process.destroy()
            task.cancel(true)
            executor.shutdownNow()
        }
    }
}
