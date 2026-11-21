package net.havocmc.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Mojang version-JSON rule evaluation (os rules + features rules).
 * Semantics: no rules -> allow. Otherwise the action of the LAST matching rule wins;
 * if no rule matches -> disallow. Unmatched feature requirements never match.
 */
public final class RuleEval {
    private RuleEval() {}

    public static boolean allow(JsonArray rules, OsUtil.Os os, OsUtil.Arch arch, JsonObject features) {
        if (rules == null || rules.size() == 0) return true;
        Boolean result = null;
        for (JsonElement el : rules) {
            if (!el.isJsonObject()) continue;
            JsonObject rule = el.getAsJsonObject();
            if (matches(rule, os, arch, features)) {
                result = "allow".equalsIgnoreCase(rule.get("action").getAsString());
            }
        }
        return Boolean.TRUE.equals(result);
    }

    private static boolean matches(JsonObject rule, OsUtil.Os os, OsUtil.Arch arch, JsonObject features) {
        if (rule.has("os") && rule.get("os").isJsonObject()) {
            JsonObject osRule = rule.getAsJsonObject("os");
            if (osRule.has("name")) {
                String name = osRule.get("name").getAsString();
                boolean hit = switch (name) {
                    case "windows" -> os == OsUtil.Os.WINDOWS;
                    case "linux" -> os == OsUtil.Os.LINUX;
                    case "osx" -> os == OsUtil.Os.MAC;
                    default -> false;
                };
                if (!hit) return false;
            }
            if (osRule.has("arch")) {
                // "x86" means 32-bit; our launcher only supports 64-bit runtimes.
                String a = osRule.get("arch").getAsString();
                if (!"x86".equals(a)) return false; // x86 rules never match on a 64-bit runtime
            }
        }
        if (rule.has("features") && rule.get("features").isJsonObject()) {
            JsonObject feats = rule.getAsJsonObject("features");
            for (String key : feats.keySet()) {
                JsonElement want = feats.get(key);
                JsonElement have = features == null ? null : features.get(key);
                if (have == null || !have.equals(want)) return false;
            }
        }
        return true;
    }
}
