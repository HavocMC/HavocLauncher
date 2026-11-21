package net.havocmc.launcher;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persisted user settings (launcher.properties). */
public final class Settings {
    /** Azure application (client) id used for the Microsoft device-code login. Blank = login disabled. */
    public String clientId = "";
    public int ramGb = 2;
    public int width = 854;
    public int height = 480;
    public boolean useCustomResolution = false;
    public boolean keepLauncherOpen = true;
    public String offlineName = "";

    private final Path file;

    private Settings(Path file) { this.file = file; }

    public static Settings load() {
        Path file = OsUtil.launcherDir().resolve("launcher.properties");
        Settings s = new Settings(file);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (Exception ignored) {}
        s.clientId = p.getProperty("client_id", "");
        s.ramGb = parseInt(p.getProperty("ram_gb"), 2);
        s.width = parseInt(p.getProperty("width"), 854);
        s.height = parseInt(p.getProperty("height"), 480);
        s.useCustomResolution = Boolean.parseBoolean(p.getProperty("custom_resolution", "false"));
        s.keepLauncherOpen = Boolean.parseBoolean(p.getProperty("keep_launcher_open", "true"));
        s.offlineName = p.getProperty("offline_name", "");
        return s;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("client_id", clientId);
        p.setProperty("ram_gb", String.valueOf(ramGb));
        p.setProperty("width", String.valueOf(width));
        p.setProperty("height", String.valueOf(height));
        p.setProperty("custom_resolution", String.valueOf(useCustomResolution));
        p.setProperty("keep_launcher_open", String.valueOf(keepLauncherOpen));
        p.setProperty("offline_name", offlineName);
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "HavocLauncher settings");
        } catch (Exception ignored) {}
    }

    private static int parseInt(String v, int def) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }
}
