package net.havocmc.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Makes sure a Java 25 runtime exists to run the game:
 * 1. already-downloaded runtime in <launcherDir>/runtime/java-25
 * 2. a system java with major >= 25 (incl. JAVA_HOME)
 * 3. downloads a Temurin JRE 25 from Adoptium (windows zip / linux+mac tar.gz)
 */
public final class JavaRuntime {
    private JavaRuntime() {}

    public static Path ensureJava(Path launcherDir, HttpUtil.Progress p) throws Exception {
        Path rtRoot = launcherDir.resolve("runtime").resolve("java-25");
        Path found = findJava(rtRoot);
        if (found != null) return found;

        Path sys = systemJava();
        if (sys != null) return sys;

        downloadRuntime(launcherDir.resolve("runtime"), p);
        found = findJava(rtRoot);
        if (found == null) throw new IOException("Java 25 runtime extraction failed");
        return found;
    }

    private static void downloadRuntime(Path runtimeDir, HttpUtil.Progress p) throws Exception {
        Files.createDirectories(runtimeDir);
        String os = OsUtil.osNameForAdoptium();
        String arch = OsUtil.archForAdoptium();
        String url = "https://api.adoptium.net/v3/binary/latest/25/ga/" + os + "/" + arch
                + "/jre/hotspot/normal/eclipse";
        Path archive = runtimeDir.resolve("java25.zip");
        HttpUtil.download(url, archive, null, null, p, "Downloading Java 25 runtime");

        Path extract = runtimeDir.resolve("java-25");
        Files.createDirectories(extract);
        if (OsUtil.os() == OsUtil.Os.WINDOWS) {
            try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
                byte[] buf = new byte[64 * 1024];
                var entry = zin.getNextEntry();
                while (entry != null) {
                    Path out = extract.resolve(entry.getName()).normalize();
                    if (!out.startsWith(extract.normalize())) { entry = zin.getNextEntry(); continue; }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (var fout = Files.newOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) fout.write(buf, 0, n);
                        }
                    }
                    entry = zin.getNextEntry();
                }
            }
        } else {
            Process proc = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", extract.toString())
                    .redirectErrorStream(true).start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(10, TimeUnit.MINUTES);
        }
        Files.deleteIfExists(archive);
    }

    private static Path findJava(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) return null;
        String exe = OsUtil.os() == OsUtil.Os.WINDOWS ? "java.exe" : "java";
        try (Stream<Path> s = Files.walk(root, 5)) {
            return s.filter(f -> f.getParent() != null && f.getParent().getFileName().toString().equals("bin")
                    && f.getFileName().toString().equals(exe)).findFirst().orElse(null);
        }
    }

    /** Returns a system java binary with major >= 25, or null. */
    public static Path systemJava() {
        for (Path candidate : new Path[]{
                Path.of(System.getProperty("java.home"), "bin", OsUtil.os() == OsUtil.Os.WINDOWS ? "java.exe" : "java"),
                Path.of(System.getenv("JAVA_HOME") == null ? "" : System.getenv("JAVA_HOME"),
                        "bin", OsUtil.os() == OsUtil.Os.WINDOWS ? "java.exe" : "java"),
                Path.of("java")}) {
            try {
                if (candidate == null || candidate.toString().isBlank() || !Files.exists(candidate)) continue;
            } catch (Exception e) { continue; }
            try {
                Process proc = new ProcessBuilder(candidate.toAbsolutePath().toString(), "-version")
                        .redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(20, TimeUnit.SECONDS);
                if (majorVersion(out) >= 25) return candidate.toAbsolutePath();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int majorVersion(String javaVersionOutput) {
        try {
            JsonObject dummy = new JsonObject();
            dummy.addProperty("x", 0);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("version \"(\\d+)(\\.(\\d+))?").matcher(javaVersionOutput);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                if (major == 1 && m.group(3) != null) return Integer.parseInt(m.group(3));
                return major;
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
