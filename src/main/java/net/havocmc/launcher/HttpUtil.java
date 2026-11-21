package net.havocmc.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Minimal HTTP helpers: downloads with progress + sha1 verify, form posts, json posts. */
public final class HttpUtil {
    public interface Progress { void update(String stage, long done, long total); }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private HttpUtil() {}

    public static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "HavocLauncher/0.1.0")
                .GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException(url + " -> HTTP " + res.statusCode());
        return res.body();
    }

    public static String postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)
                        + "=" + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "HavocLauncher/0.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + "\n" + res.body();
    }

    public static String postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "HavocLauncher/0.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + "\n" + res.body();
    }

    public static String postJsonAuth(String url, String json, String bearer) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("User-Agent", "HavocLauncher/0.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + "\n" + res.body();
    }

    public static String getAuth(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("User-Agent", "HavocLauncher/0.1.0")
                .GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + "\n" + res.body();
    }

    /** Downloads url to dest (atomically via .part). Skips if dest exists with correct sha1 or size. */
    public static void download(String url, Path dest, String sha1, Long expectedSize, Progress p, String stage)
            throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest)) {
            if (sha1 != null && sha1(dest).equalsIgnoreCase(sha1)) return;
            if (sha1 == null && expectedSize != null && Files.size(dest) == expectedSize) return;
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                downloadOnce(url, dest, expectedSize, p, stage);
                if (sha1 != null && !sha1(dest).equalsIgnoreCase(sha1)) {
                    Files.deleteIfExists(dest);
                    throw new IOException("sha1 mismatch for " + dest.getFileName());
                }
                return;
            } catch (IOException e) {
                last = e;
                Thread.sleep(800L * (attempt + 1));
            }
        }
        throw last;
    }

    private static void downloadOnce(String url, Path dest, Long expectedSize, Progress p, String stage)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "HavocLauncher/0.1.0")
                .GET().build();
        HttpResponse<InputStream> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) throw new IOException(url + " -> HTTP " + res.statusCode());
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        try (InputStream in = res.body(); var out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null) p.update(stage, done, total > 0 ? total : expectedSize);
            }
        }
        if (expectedSize != null && Files.size(part) != expectedSize) {
            Files.deleteIfExists(part);
            throw new IOException("size mismatch for " + dest.getFileName());
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
