import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts an MCP server of the Claude plugin: java Launcher.java <tuwel|lecturetube> <pluginRoot> <pluginData>.
 * Uses build/libs/<server>-mcp.jar of a local build if present, otherwise the jar of the GitHub release that matches
 * the plugin version, downloaded once into the plugin data folder and checked against the SHA-256 the release
 * workflow recorded in .claude-plugin/jars.sha256. Only stderr is used: stdout carries MCP messages.
 */
public class Launcher {
    static final String RELEASES = "https://github.com/Swordpro3001/TUWIEN-MCP/releases/download/";

    public static void main(String[] args) throws Exception {
        String server = args[0];
        Path root = Path.of(args[1]);
        Path data = Path.of(args.length > 2 && !args[2].isBlank() ? args[2] : args[1]);
        String jarName = server + "-mcp.jar";
        String mainClass = server.equals("tuwel") ? "dev.tuwelmcp.MainKt" : "dev.lecturetubemcp.MainKt";

        Path jar = root.resolve("build/libs").resolve(jarName);
        if (!Files.isRegularFile(jar)) {
            String version = version(root);
            jar = data.resolve("jars").resolve(version).resolve(jarName);
            if (!Files.isRegularFile(jar)) download(RELEASES + "v" + version + "/" + jarName, jar, expectedSha256(root, jarName));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, Launcher.class.getClassLoader().getParent())) {
            Thread.currentThread().setContextClassLoader(loader);
            Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        }
    }

    static String version(Path root) throws Exception {
        String manifest = Files.readString(root.resolve(".claude-plugin/plugin.json"));
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(manifest);
        if (!m.find()) throw new IllegalStateException("No version in .claude-plugin/plugin.json");
        return m.group(1);
    }

    static String expectedSha256(Path root, String jarName) throws Exception {
        Path sums = root.resolve(".claude-plugin/jars.sha256");
        if (Files.isRegularFile(sums)) {
            for (String line : Files.readAllLines(sums)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(jarName)) return parts[0].toLowerCase();
            }
        }
        throw new IllegalStateException("No checksum for " + jarName + " in " + sums + "; refusing to run an unverified download.");
    }

    static void download(String url, Path target, String sha256) throws Exception {
        System.err.println("Downloading " + url);
        Files.createDirectories(target.getParent());
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IllegalStateException("Download failed (HTTP " + resp.statusCode() + "): " + url);
        Path tmp = Files.createTempFile(target.getParent(), "download", ".tmp");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp)));
        if (!actual.equals(sha256)) {
            Files.delete(tmp);
            throw new IllegalStateException("Checksum mismatch for " + url + ": expected " + sha256 + ", got " + actual);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
