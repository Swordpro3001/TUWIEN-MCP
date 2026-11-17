package dev.lecturetubemcp.media

import java.util.concurrent.TimeUnit

/**
 * Desktop notifications for the user (the model cannot be woken up by a server, but the user can).
 * Best effort: notify-send (Linux), osascript (macOS) or a PowerShell balloon (Windows). Disabled with
 * TRANSCRIBE_NOTIFY=0.
 */
object Notifier {
    val enabled: Boolean get() = System.getenv("TRANSCRIBE_NOTIFY")?.lowercase() !in setOf("0", "false", "no", "off")

    fun notify(title: String, body: String) {
        if (!enabled) return
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("osascript", "-e", "display notification ${osa(body)} with title ${osa(title)}")
            os.contains("win") -> listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; \$n = New-Object System.Windows.Forms.NotifyIcon; " +
                    "\$n.Icon = [System.Drawing.SystemIcons]::Information; \$n.Visible = \$true; " +
                    "\$n.ShowBalloonTip(10000, ${ps(title)}, ${ps(body)}, 'Info'); Start-Sleep -Seconds 10; \$n.Dispose()",
            )
            else -> listOf("notify-send", "--app-name=Lecture transcripts", "--icon=audio-x-generic", title, body)
        }
        runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            // Don't block the job; reap the process in the background.
            Thread { runCatching { if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy() } }.apply { isDaemon = true }.start()
        }
    }

    private fun osa(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun ps(s: String) = "'" + s.replace("'", "''") + "'"
}
