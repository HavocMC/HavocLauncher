package net.havocmc.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/** OS / arch detection and well-known directories. */
public final class OsUtil {
    public enum Os { WINDOWS, LINUX, MAC }
    public enum Arch { X64, ARM64 }

    private OsUtil() {}

    public static Os os() {
        String n = System.getProperty("os.name", "").toLowerCase();
        if (n.contains("win")) return Os.WINDOWS;
        if (n.contains("mac") || n.contains("darwin")) return Os.MAC;
        return Os.LINUX;
    }

    public static Arch arch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return (a.contains("aarch64") || a.contains("arm64")) ? Arch.ARM64 : Arch.X64;
    }

    public static String osNameForAdoptium() {
        return switch (os()) { case WINDOWS -> "windows"; case MAC -> "mac"; case LINUX -> "linux"; };
    }

    public static String archForAdoptium() {
        return arch() == Arch.ARM64 ? "aarch64" : "x64";
    }

    /** Directory where the launcher keeps versions, libraries, assets, runtime and mods. */
    public static Path launcherDir() {
        try {
            Path base;
            switch (os()) {
                case WINDOWS -> {
                    String appdata = System.getenv("APPDATA");
                    base = (appdata != null && !appdata.isBlank())
                            ? Path.of(appdata) : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
                    base = base.resolve(".havoclauncher");
                }
                case MAC -> base = Path.of(System.getProperty("user.home"),
                        "Library", "Application Support", "havoclauncher");
                default -> base = Path.of(System.getProperty("user.home"), ".havoclauncher");
            }
            Files.createDirectories(base);
            return base;
        } catch (Exception e) {
            throw new RuntimeException("Cannot create launcher directory", e);
        }
    }

    /** The vanilla .minecraft directory (may be null). Used to reuse downloaded assets. */
    public static Path officialMinecraftDir() {
        Path p;
        switch (os()) {
            case WINDOWS -> {
                String appdata = System.getenv("APPDATA");
                p = (appdata != null && !appdata.isBlank())
                        ? Path.of(appdata, ".minecraft")
                        : Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft");
            }
            case MAC -> p = Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft");
            default -> p = Path.of(System.getProperty("user.home"), ".minecraft");
        }
        return Files.isDirectory(p) ? p : null;
    }
}
