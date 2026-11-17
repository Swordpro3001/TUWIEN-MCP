package dev.lecturetubemcp

import java.nio.file.Path
import java.nio.file.Paths

object Config {
    const val VERSION = "0.1.0"

    /** Tobira portal: GraphQL, feeds, Shibboleth SP #1. */
    const val TOBIRA = "https://video.tuwien.ac.at"
    /** Opencast presentation node: Search API, Shibboleth SP #2. */
    const val OPENCAST = "https://lecturetube.video.tuwien.ac.at"

    const val TOBIRA_HOST = "video.tuwien.ac.at"
    const val OPENCAST_HOST = "lecturetube.video.tuwien.ac.at"

    /** Shibboleth login on the portal. After login the SP redirects to `target`. */
    const val TOBIRA_LOGIN_URL = "$TOBIRA/Shibboleth.sso/Login?target=%2F~session"
    /** Second SP. With an existing IdP session this completes without user interaction. */
    const val OPENCAST_LOGIN_URL = "$OPENCAST/Shibboleth.sso/Login?target=%2Finfo%2Fme.json"

    /** TUWEL (Moodle 5.x) with SAML2 SSO through the same TU Wien IdP. */
    const val TUWEL = "https://tuwel.tuwien.ac.at"
    const val TUWEL_HOST = "tuwel.tuwien.ac.at"
    const val TUWEL_LOGIN_URL = "$TUWEL/auth/saml2/login.php?wants=%2Fmy%2F&idp=7bdd808f9f4da82bfe7992e779794b9a&passive=off"

    /** TU Wien identity provider (SimpleSAMLphp). Its session cookie enables silent SSO for all services. */
    const val IDP_HOST = "idp.zid.tuwien.ac.at"

    /** Hosts whose cookies are captured from the login browser and stored. */
    val SESSION_HOSTS = listOf(TOBIRA_HOST, OPENCAST_HOST, "cdn.video.tuwien.ac.at", TUWEL_HOST, IDP_HOST)

    const val USER_AGENT = "lecturetube-mcp/$VERSION (+unofficial MCP server)"

    /** Base directory for session data and the dedicated login browser profile. */
    val dataDir: Path by lazy {
        (System.getProperty("lecturetubemcp.home") ?: System.getenv("LECTURETUBE_MCP_HOME"))?.let { Paths.get(it) }
            ?: run {
                val home = System.getProperty("user.home")
                val os = System.getProperty("os.name").lowercase()
                when {
                    os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home, "lecturetube-mcp")
                    os.contains("mac") -> Paths.get(home, "Library", "Application Support", "lecturetube-mcp")
                    else -> Paths.get(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "lecturetube-mcp")
                }
            }
    }

    val sessionFile: Path get() = dataDir.resolve("session.json")
}
