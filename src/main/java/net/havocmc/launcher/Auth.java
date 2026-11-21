package net.havocmc.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Microsoft account login for Minecraft: Java Edition.
 * Device-code OAuth flow (needs your own Azure app client_id in launcher.properties),
 * token refresh via refresh_token, plus an offline (username-only) fallback.
 */
public final class Auth {
    public static final String SCOPE = "XboxLive.signin offline_access";

    public record Account(String name, String uuid, String accessToken, long expiresAtMs,
                          String refreshToken, String xuid, boolean offline) {}

    public interface DeviceCodeUi {
        /** Show the code; return false if the user cancelled. */
        boolean showCode(String userCode, String verificationUri);
        /** True when the user closed / cancelled the dialog (checked between poll attempts). */
        boolean isCancelled();
        void done();
    }

    private Auth() {}

    // ------------------------------------------------------------------ public API

    public static synchronized Account login(Settings settings, DeviceCodeUi ui) throws Exception {
        if (settings.clientId == null || settings.clientId.isBlank()) {
            if (settings.offlineName == null || settings.offlineName.isBlank()) {
                throw new IllegalStateException("No Microsoft client_id configured and no offline username set.");
            }
            return offline(settings.offlineName);
        }

        Account cached = loadCache();
        long now = System.currentTimeMillis();
        if (cached != null && !cached.offline() && cached.accessToken != null && cached.expiresAtMs > now + 60_000) {
            return cached;
        }
        if (cached != null && cached.refreshToken != null && !cached.refreshToken.isBlank()) {
            try {
                Account refreshed = refresh(cached.refreshToken, settings.clientId);
                saveCache(refreshed);
                return refreshed;
            } catch (Exception ignored) { /* fall through to device code */ }
        }

        // device code flow
        String resp = HttpUtil.postForm(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                Map.of("client_id", settings.clientId, "scope", SCOPE));
        JsonObject dc = body(resp);
        if (dc == null || !dc.has("device_code")) throw new IllegalStateException("Device code request failed: " + resp);
        String deviceCode = dc.get("device_code").getAsString();
        String userCode = dc.get("user_code").getAsString();
        String verifyUri = dc.has("verification_uri") ? dc.get("verification_uri").getAsString()
                : "https://www.microsoft.com/link";
        int interval = dc.has("interval") ? dc.get("interval").getAsInt() : 5;
        long expires = System.currentTimeMillis() + (dc.has("expires_in") ? dc.get("expires_in").getAsInt() : 900) * 1000L;

        boolean proceed = ui.showCode(userCode, verifyUri);
        if (!proceed) throw new IllegalStateException("Login cancelled.");
        try {
            while (System.currentTimeMillis() < expires) {
                if (ui.isCancelled()) throw new IllegalStateException("Login cancelled.");
                Thread.sleep(interval * 1000L);
                String tokResp = HttpUtil.postForm(
                        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                        Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id", settings.clientId, "device_code", deviceCode));
                JsonObject tok = body(tokResp);
                if (tok == null) continue;
                if (tok.has("access_token")) {
                    Account acc = chain(tok.get("access_token").getAsString(),
                            tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : null);
                    saveCache(acc);
                    return acc;
                }
                if (tok.has("error")) {
                    String err = tok.get("error").getAsString();
                    switch (err) {
                        case "authorization_pending" -> { /* keep polling */ }
                        case "slow_down" -> interval += 5;
                        case "expired_token" -> throw new IllegalStateException("Login code expired, try again.");
                        case "authorization_declined" -> throw new IllegalStateException("Login was declined.");
                        default -> throw new IllegalStateException("Login failed: " + err);
                    }
                }
            }
            throw new IllegalStateException("Login timed out.");
        } finally {
            ui.done();
        }
    }

    public static Account offline(String name) {
        name = name.trim();
        if (name.isEmpty()) throw new IllegalStateException("Enter a username first.");
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Account acc = new Account(name, uuid.toString(), "0", Long.MAX_VALUE, null, "0", true);
        saveCache(acc);
        return acc;
    }

    public static synchronized Account loadCache() {
        Path f = cachePath();
        try {
            if (!Files.exists(f)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            return new Account(o.get("name").getAsString(), o.get("uuid").getAsString(),
                    o.get("access_token").getAsString(), o.get("expires_at").getAsLong(),
                    o.has("refresh_token") ? o.get("refresh_token").getAsString() : null,
                    o.has("xuid") ? o.get("xuid").getAsString() : "0",
                    o.has("offline") && o.get("offline").getAsBoolean());
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void saveCache(Account acc) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("name", acc.name());
            o.addProperty("uuid", acc.uuid());
            o.addProperty("access_token", acc.accessToken());
            o.addProperty("expires_at", acc.expiresAtMs());
            if (acc.refreshToken() != null) o.addProperty("refresh_token", acc.refreshToken());
            o.addProperty("xuid", acc.xuid());
            o.addProperty("offline", acc.offline());
            Files.writeString(cachePath(), o.toString());
        } catch (Exception ignored) {}
    }

    public static synchronized void clearCache() {
        try { Files.deleteIfExists(cachePath()); } catch (Exception ignored) {}
    }

    private static Path cachePath() {
        return OsUtil.launcherDir().resolve("auth.json");
    }

    // ------------------------------------------------------------------ msa / xbl chain

    private static Account refresh(String refreshToken, String clientId) throws Exception {
        String resp = HttpUtil.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken));
        JsonObject tok = body(resp);
        if (tok == null || !tok.has("access_token")) throw new IllegalStateException("Token refresh failed: " + resp);
        return chain(tok.get("access_token").getAsString(),
                tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : refreshToken);
    }

    private static Account chain(String msaToken, String msaRefresh) throws Exception {
        // XBL
        JsonObject xblReq = new JsonObject();
        JsonObject xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RPS");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProps.addProperty("RpsTicket", "d=" + msaToken);
        xblReq.add("Properties", xblProps);
        xblReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblReq.addProperty("TokenType", "JWT");
        String xblResp = HttpUtil.postJson("https://user.auth.xboxlive.com/user/authenticate", xblReq.toString());
        JsonObject xbl = body(xblResp);
        if (xbl == null || !xbl.has("Token")) throw new IllegalStateException("Xbox Live auth failed: " + xblResp);
        String xblToken = xbl.get("Token").getAsString();
        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();

        // XSTS
        JsonObject xstsReq = new JsonObject();
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        xstsProps.add("UserTokens", userTokens);
        xstsReq.add("Properties", xstsProps);
        xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.addProperty("TokenType", "JWT");
        String xstsResp = HttpUtil.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq.toString());
        JsonObject xsts = body(xstsResp);
        if (xsts == null || !xsts.has("Token")) {
            String hint = "";
            if (xstsResp.contains("2148916233")) hint = " (this Microsoft account has no Xbox profile)";
            if (xstsResp.contains("2148916238")) hint = " (account is a child account / age restrictions)";
            throw new IllegalStateException("Xbox Live security token failed" + hint + ": " + xstsResp);
        }
        String xstsToken = xsts.get("Token").getAsString();
        String xuid = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject()
                .has("xid") ? xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("xid").getAsString() : "0";

        // Minecraft services
        JsonObject loginReq = new JsonObject();
        loginReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        String mcResp = HttpUtil.postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox", loginReq.toString());
        JsonObject mc = body(mcResp);
        if (mc == null || !mc.has("access_token")) throw new IllegalStateException("Minecraft login failed: " + mcResp);
        String mcToken = mc.get("access_token").getAsString();
        long expiresAt = System.currentTimeMillis()
                + (mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400) * 1000L;

        String profileResp = HttpUtil.getAuth("https://api.minecraftservices.com/minecraft/profile", mcToken);
        JsonObject profile = body(profileResp);
        if (profile == null || !profile.has("name")) {
            throw new IllegalStateException("This Microsoft account does not own Minecraft: Java Edition.");
        }
        return new Account(profile.get("name").getAsString(), profile.get("id").getAsString(),
                mcToken, expiresAt, msaRefresh, xuid, false);
    }

    private static JsonObject body(String statusBody) {
        int nl = statusBody.indexOf('\n');
        String bodyStr = nl >= 0 ? statusBody.substring(nl + 1) : statusBody;
        try {
            JsonElement el = JsonParser.parseString(bodyStr);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
