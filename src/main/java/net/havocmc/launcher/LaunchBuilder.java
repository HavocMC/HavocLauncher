package net.havocmc.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the full JVM command line to start Minecraft 26.3 via Fabric loader (KnotClient). */
public final class LaunchBuilder {
    private LaunchBuilder() {}

    public static List<String> build(Settings settings, Installer.Result prepared,
                                     Auth.Account account, Path javaExe) {
        JsonObject merged = prepared.merged();
        Path gameDir = prepared.gameDir();
        Path nativesDir = gameDir.resolve("versions").resolve(Installer.MC_VERSION).resolve("natives");

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", account.name());
        vars.put("version_name", merged.get("id").getAsString());
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", prepared.assetsRoot().toString());
        vars.put("assets_index_name", prepared.assetIndexId());
        vars.put("auth_uuid", account.uuid());
        vars.put("auth_access_token", account.accessToken());
        vars.put("auth_session", account.accessToken());
        vars.put("auth_xuid", account.xuid());
        vars.put("clientid", settings.clientId == null || settings.clientId.isBlank() ? "0" : settings.clientId);
        vars.put("user_type", account.offline() ? "legacy" : "msa");
        vars.put("version_type", "release");
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "HavocLauncher");
        vars.put("launcher_version", "0.1.0");
        vars.put("resolution_width", String.valueOf(settings.width));
        vars.put("resolution_height", String.valueOf(settings.height));
        vars.put("userProperties", "{}");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.add("-Xmx" + settings.ramGb + "G");
        cmd.add("-Xms512m");
        cmd.add("-Dfabric.modsFolder=" + gameDir.resolve("mods").toString());

        JsonObject features = new JsonObject();
        if (settings.useCustomResolution) features.addProperty("has_custom_resolution", true);

        // JVM args (includes -cp ${classpath} inside the version JSON)
        Map<String, String> launchVars = new HashMap<>(vars);
        launchVars.put("classpath", joinClasspath(prepared.classpath()));
        expand(merged.getAsJsonObject("arguments").getAsJsonArray("jvm"), features, launchVars, cmd);

        // Main class (KnotClient from the Fabric profile)
        cmd.add(merged.get("mainClass").getAsString());

        // Game args
        expand(merged.getAsJsonObject("arguments").getAsJsonArray("game"), features, vars, cmd);
        return cmd;
    }

    private static String joinClasspath(List<Path> cp) {
        StringBuilder sb = new StringBuilder();
        for (Path p : cp) {
            if (sb.length() > 0) sb.append(java.io.File.pathSeparator);
            sb.append(p);
        }
        return sb.toString();
    }

    /** Expands a version-json argument array: applies rules, unwraps rule-gated values, substitutes variables. */
    private static void expand(JsonArray args, JsonObject features, Map<String, String> vars, List<String> out) {
        for (JsonElement el : args) {
            if (el.isJsonPrimitive()) {
                out.add(substitute(el.getAsString(), vars));
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                JsonArray rules = obj.has("rules") ? obj.getAsJsonArray("rules") : null;
                if (!RuleEval.allow(rules, OsUtil.os(), OsUtil.arch(), features)) continue;
                JsonElement value = obj.get("value");
                if (value == null) continue;
                if (value.isJsonPrimitive()) {
                    out.add(substitute(value.getAsString(), vars));
                } else if (value.isJsonArray()) {
                    for (JsonElement v : value.getAsJsonArray()) out.add(substitute(v.getAsString(), vars));
                }
            }
        }
    }

    private static String substitute(String s, Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int start = s.indexOf("${", i);
            if (start < 0) { sb.append(s, i, s.length()); break; }
            int end = s.indexOf('}', start);
            if (end < 0) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, start);
            sb.append(vars.getOrDefault(s.substring(start + 2, end), ""));
            i = end + 1;
        }
        return sb.toString();
    }
}
