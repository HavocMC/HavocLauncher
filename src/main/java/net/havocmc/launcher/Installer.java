package net.havocmc.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Prepares everything needed to launch Minecraft 26.3 with Fabric + HavocClient:
 * vanilla version JSON, Fabric loader profile (inheritsFrom), merged profile,
 * client jar, libraries, assets (reuses the vanilla .minecraft assets when present),
 * natives dirs and mods (bundled inside this launcher).
 */
public final class Installer {
    public static final String MC_VERSION = "26.3";
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record Result(JsonObject merged, Path gameDir, Path assetsRoot, String assetIndexId,
                         List<Path> classpath, List<Path> mods) {}

    private final Path dir;
    private final HttpUtil.Progress progress;
    private final boolean testSkipAssetObjects;

    public Installer(Path dir, HttpUtil.Progress progress, boolean testSkipAssetObjects) {
        this.dir = dir;
        this.progress = progress;
        this.testSkipAssetObjects = testSkipAssetObjects;
    }

    public Result prepare() throws Exception {
        Files.createDirectories(dir.resolve("versions").resolve(MC_VERSION).resolve("natives"));

        // 1) Vanilla version JSON -------------------------------------------------
        Path vanillaJsonPath = dir.resolve("versions").resolve(MC_VERSION).resolve(MC_VERSION + ".json");
        if (!Files.exists(vanillaJsonPath)) {
            String manifest = HttpUtil.get(MANIFEST_URL);
            JsonObject root = JsonParser.parseString(manifest).getAsJsonObject();
            String url = null;
            for (JsonElement v : root.getAsJsonArray("versions")) {
                if (MC_VERSION.equals(v.getAsJsonObject().get("id").getAsString())) {
                    url = v.getAsJsonObject().get("url").getAsString();
                    break;
                }
            }
            if (url == null) throw new IOException("Minecraft " + MC_VERSION + " not found in version manifest");
            HttpUtil.download(url, vanillaJsonPath, null, null, progress, "Fetching version info");
        }
        JsonObject vanilla = JsonParser.parseString(Files.readString(vanillaJsonPath)).getAsJsonObject();

        // 2) Fabric loader profile ------------------------------------------------
        JsonObject loaderInfo = JsonParser.parseString(
                HttpUtil.get("https://meta.fabricmc.net/v2/versions/loader/" + MC_VERSION))
                .getAsJsonArray().get(0).getAsJsonObject();
        String loaderVersion = loaderInfo.getAsJsonObject("loader").get("version").getAsString();
        Path fabricJsonPath = dir.resolve("versions")
                .resolve("fabric-loader-" + loaderVersion + "-" + MC_VERSION + ".json");
        if (!Files.exists(fabricJsonPath)) {
            String body = HttpUtil.get("https://meta.fabricmc.net/v2/versions/loader/" + MC_VERSION
                    + "/" + loaderVersion + "/profile/json");
            Files.writeString(fabricJsonPath, body);
        }
        JsonObject fabric = JsonParser.parseString(Files.readString(fabricJsonPath)).getAsJsonObject();

        // 3) Merge (vanilla as parent, fabric as child) ---------------------------
        JsonObject merged = merge(vanilla, fabric);
        String fabricId = merged.get("id").getAsString();
        Path mergedPath = dir.resolve("versions").resolve(fabricId + ".json");
        Files.writeString(mergedPath, merged.toString());

        // 4) Client jar ------------------------------------------------------------
        JsonObject clientDl = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = dir.resolve("versions").resolve(MC_VERSION).resolve(MC_VERSION + ".jar");
        HttpUtil.download(clientDl.get("url").getAsString(), clientJar,
                clientDl.has("sha1") ? clientDl.get("sha1").getAsString() : null,
                clientDl.has("size") ? clientDl.get("size").getAsLong() : null,
                progress, "Downloading Minecraft " + MC_VERSION);

        // 5) Libraries (os rules applied) -----------------------------------------
        List<Path> cp = new ArrayList<>();
        int i = 0;
        for (JsonElement le : merged.getAsJsonArray("libraries")) {
            JsonObject lib = le.getAsJsonObject();
            if (!RuleEval.allow(lib.has("rules") ? lib.getAsJsonArray("rules") : null,
                    OsUtil.os(), OsUtil.arch(), null)) continue;
            Resolved r = resolve(lib);
            if (r == null) continue;
            Path dest = dir.resolve("libraries").resolve(r.mavenPath());
            HttpUtil.download(r.url(), dest, r.sha1(), r.size(), null, "libraries");
            cp.add(dest);
            if (progress != null && (++i % 5 == 0)) progress.update("Libraries (" + i + ")", 1, 1);
        }
        cp.add(clientJar);

        // 6) Assets (reuse vanilla .minecraft assets when present) -----------------
        JsonObject assetIndexMeta = vanilla.getAsJsonObject("assetIndex");
        String indexId = assetIndexMeta.get("id").getAsString();
        Path assetsRoot;
        Path official = OsUtil.officialMinecraftDir();
        Path officialAssets = official != null ? official.resolve("assets") : null;
        Path officialIndex = officialAssets != null ? officialAssets.resolve("indexes").resolve(indexId + ".json") : null;
        if (officialIndex != null && Files.exists(officialIndex)) {
            assetsRoot = officialAssets;
        } else {
            assetsRoot = dir.resolve("assets");
            Path indexFile = assetsRoot.resolve("indexes").resolve(indexId + ".json");
            HttpUtil.download(assetIndexMeta.get("url").getAsString(), indexFile,
                    assetIndexMeta.has("sha1") ? assetIndexMeta.get("sha1").getAsString() : null,
                    assetIndexMeta.has("size") ? assetIndexMeta.get("size").getAsLong() : null,
                    progress, "Fetching asset index");
            JsonObject index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
            JsonObject objects = index.getAsJsonObject("objects");
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>();
            objects.entrySet().forEach(entries::add);
            int done = 0;
            for (Map.Entry<String, JsonElement> e : entries) {
                if (testSkipAssetObjects) break;
                JsonObject o = e.getValue().getAsJsonObject();
                String hash = o.get("hash").getAsString();
                Path dest = assetsRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                if (Files.exists(dest) && Files.size(dest) == o.get("size").getAsLong()) {
                    done++;
                } else {
                    String u = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
                    HttpUtil.download(u, dest, hash, o.get("size").getAsLong(), null, "assets");
                    done++;
                }
                if (done % 40 == 0 && progress != null) {
                    progress.update("Assets " + done + "/" + entries.size(), done, entries.size());
                }
            }
        }

        // 7) Natives directories (LWJGL / netty extract into these) ---------------
        for (String sub : new String[]{"java", "jna", "lwjgl", "netty"}) {
            Files.createDirectories(dir.resolve("versions").resolve(MC_VERSION)
                    .resolve("natives").resolve(sub));
        }

        // 8) Mods (bundled inside this launcher jar) -------------------------------
        Path modsDir = dir.resolve("mods");
        Files.createDirectories(modsDir);
        List<Path> mods = extractBundledMods(modsDir);

        return new Result(merged, dir, assetsRoot, indexId, cp, mods);
    }

    private JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject out = new JsonObject();
        copyBasics(parent, out);
        out.addProperty("id", child.get("id").getAsString());
        if (child.has("mainClass")) out.addProperty("mainClass", child.get("mainClass").getAsString());
        if (child.has("type")) out.addProperty("type", child.get("type").getAsString());
        out.addProperty("inheritsFrom", MC_VERSION);

        // libraries: parent + child (de-duplicated by maven name; parent's metadata wins)
        JsonArray libs = new JsonArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (parent.has("libraries")) {
            for (JsonElement e : parent.getAsJsonArray("libraries")) {
                libs.add(e);
                seen.add(e.getAsJsonObject().get("name").getAsString());
            }
        }
        if (child.has("libraries")) {
            for (JsonElement e : child.getAsJsonArray("libraries")) {
                if (seen.add(e.getAsJsonObject().get("name").getAsString())) libs.add(e);
            }
        }
        out.add("libraries", libs);

        // arguments: parent first, then child
        JsonObject args = new JsonObject();
        for (String kind : new String[]{"game", "jvm"}) {
            JsonArray arr = new JsonArray();
            if (parent.has("arguments")) {
                JsonElement pa = parent.getAsJsonObject("arguments").get(kind);
                if (pa != null && pa.isJsonArray()) for (JsonElement e : pa.getAsJsonArray()) arr.add(e);
            }
            if (child.has("arguments")) {
                JsonElement ca = child.getAsJsonObject("arguments").get(kind);
                if (ca != null && ca.isJsonArray()) for (JsonElement e : ca.getAsJsonArray()) arr.add(e);
            }
            args.add(kind, arr);
        }
        out.add("arguments", args);
        return out;
    }

    private void copyBasics(JsonObject from, JsonObject to) {
        for (String key : new String[]{"minecraftArguments", "assetIndex", "assets", "downloads",
                "javaVersion", "releaseTime", "time", "rules", "logging", "complianceLevel"}) {
            if (from.has(key)) to.add(key, from.get(key));
        }
    }

    /** Library entry -> {download url, maven-relative path, sha1, size}. Handles both Mojang and Fabric formats. */
    private record Resolved(String url, String mavenPath, String sha1, Long size) {}

    private static Resolved resolve(JsonObject lib) {
        JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
        JsonObject artifact = downloads != null && downloads.has("artifact") && downloads.get("artifact").isJsonObject()
                ? downloads.getAsJsonObject("artifact") : null;
        if (artifact != null && artifact.has("url")) {
            String url = artifact.get("url").getAsString();
            if (url.isBlank()) return null;
            String path = artifact.has("path") && !artifact.get("path").isJsonNull()
                    ? artifact.get("path").getAsString()
                    : URI.create(url).getPath().substring(1);
            String sha1 = artifact.has("sha1") && !artifact.get("sha1").isJsonNull()
                    ? artifact.get("sha1").getAsString() : null;
            Long size = artifact.has("size") && !artifact.get("size").isJsonNull()
                    ? artifact.get("size").getAsLong() : null;
            return new Resolved(url, path, sha1, size);
        }
        // Maven coordinates only (Fabric loader libraries: name + repo url + sha1/size)
        String name = lib.has("name") ? lib.get("name").getAsString() : null;
        if (name == null) return null;
        String[] p = name.split(":");
        if (p.length < 3) return null;
        StringBuilder jar = new StringBuilder(p[1]).append('-').append(p[2]);
        if (p.length > 3 && !p[3].isBlank()) jar.append('-').append(p[3]);
        jar.append(".jar");
        String mavenPath = p[0].replace('.', '/') + '/' + p[1] + '/' + p[2] + '/' + jar;
        String base = lib.has("url") && !lib.get("url").isJsonNull() && !lib.get("url").getAsString().isBlank()
                ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
        if (!base.endsWith("/")) base += "/";
        String sha1 = lib.has("sha1") && !lib.get("sha1").isJsonNull() ? lib.get("sha1").getAsString() : null;
        Long size = lib.has("size") && !lib.get("size").isJsonNull() ? lib.get("size").getAsLong() : null;
        return new Resolved(base + mavenPath, mavenPath, sha1, size);
    }

    /** Copies mod jars bundled inside this launcher (resources/mods/*.jar) into the game mods dir. */
    private List<Path> extractBundledMods(Path modsDir) throws Exception {
        List<Path> mods = new ArrayList<>();
        URI self = Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        File selfFile = new File(self);
        if (selfFile.isDirectory()) {
            Path bundled = selfFile.toPath().resolve("mods");
            if (Files.isDirectory(bundled)) {
                try (var s = Files.list(bundled)) {
                    s.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                        try {
                            Path dst = modsDir.resolve(p.getFileName().toString());
                            Files.copy(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            mods.add(dst);
                        } catch (Exception ignored) {}
                    });
                }
            }
            return mods;
        }
        try (JarFile jf = new JarFile(selfFile)) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith("mods/") && name.endsWith(".jar")) {
                    Path dst = modsDir.resolve(name.substring("mods/".length()));
                    try (InputStream in = jf.getInputStream(entry)) {
                        Files.copy(in, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    mods.add(dst);
                }
            }
        }
        return mods;
    }
}
