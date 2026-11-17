package dev.lecturetubemcp

import dev.lecturetubemcp.auth.BrowserKind
import dev.lecturetubemcp.auth.DefaultBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultBrowserTest {
    @Test fun parsesDesktopEntryMainGroupOnly() {
        val lines = """
            [Desktop Entry]
            Name=Opera GX
            Exec=opera-gx %U
            [Desktop Action new-private-window]
            Name=New Private Window
            Exec=opera-gx --private %U
        """.trimIndent().lines()
        assertEquals("Opera GX" to "opera-gx %U", DefaultBrowser.parseDesktopEntry(lines))
    }

    @Test fun resolvesCommands() {
        assertEquals(listOf("/opt/google/chrome/google-chrome"), DefaultBrowser.resolveCommand("/opt/google/chrome/google-chrome %U"))
        assertEquals(listOf("/usr/bin/firefox"), DefaultBrowser.resolveCommand("env MOZ_ENABLE_WAYLAND=1 /usr/bin/firefox --name firefox %u"))
        val fp = DefaultBrowser.resolveCommand("/usr/bin/flatpak run --branch=stable --arch=x86_64 --command=firefox --file-forwarding org.mozilla.firefox @@u %u @@")
        assertEquals(listOf("run", "org.mozilla.firefox"), fp.drop(1))
    }

    @Test fun classifiesBrowsers() {
        assertEquals(BrowserKind.CHROMIUM, DefaultBrowser.classify("opera-gx.desktop", "/usr/bin/opera-gx"))
        assertEquals(BrowserKind.CHROMIUM, DefaultBrowser.classify("com.microsoft.edgemac"))
        assertEquals(BrowserKind.CHROMIUM, DefaultBrowser.classify("ChromeHTML", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
        assertEquals(BrowserKind.FIREFOX, DefaultBrowser.classify("org.mozilla.firefox.desktop"))
        assertEquals(BrowserKind.FIREFOX, DefaultBrowser.classify("zen.desktop"))
        assertNull(DefaultBrowser.classify("com.apple.safari"))
        assertTrue(DefaultBrowser.classify("org.gnome.Epiphany.desktop") == null)
    }
}
