package net.havocmc.launcher;

import java.nio.file.Path;
import java.util.List;

/** Entry point: GUI by default, `--test-prepare` runs a headless install + command build test. */
public final class LauncherMain {
    public static void main(String[] args) throws Exception {
        boolean test = false;
        boolean testAssets = false;
        for (String a : args) {
            if (a.equals("--test-prepare")) test = true;
            if (a.equals("--test-assets")) testAssets = true;
        }
        if (test) {
            runTest(testAssets);
            return;
        }
        Settings settings = Settings.load();
        javax.swing.SwingUtilities.invokeLater(() -> new LauncherUI(settings).setVisible(true));
    }

    private static void runTest(boolean testAssets) throws Exception {
        Path dir = OsUtil.launcherDir();
        System.out.println("[test] launcher dir: " + dir);
        Settings settings = Settings.load();

        long t0 = System.currentTimeMillis();
        Path javaExe = JavaRuntime.ensureJava(dir, (stage, done, total) ->
                System.out.println("[progress] " + stage + (total > 0 ? " " + done * 100 / total + "%" : "")));
        System.out.println("[test] java: " + javaExe + " (" + (System.currentTimeMillis() - t0) + " ms)");

        Installer installer = new Installer(dir, (stage, done, total) ->
                System.out.println("[progress] " + stage + (total > 0 ? " " + done * 100 / total + "%" : "")),
                !testAssets);
        Installer.Result prepared = installer.prepare();
        System.out.println("[test] merged id: " + prepared.merged().get("id").getAsString());
        System.out.println("[test] mainClass: " + prepared.merged().get("mainClass").getAsString());
        System.out.println("[test] assetIndex: " + prepared.assetIndexId() + " at " + prepared.assetsRoot());
        System.out.println("[test] classpath entries: " + prepared.classpath().size());
        System.out.println("[test] mods:");
        for (Path m : prepared.mods()) System.out.println("  - " + m.getFileName());

        Auth.Account acc = Auth.offline("HavocTester");
        List<String> cmd = LaunchBuilder.build(settings, prepared, acc, javaExe);
        System.out.println("[test] command (" + cmd.size() + " parts):");
        System.out.println(String.join("\n    ", cmd));
        System.out.println("[test] OK — pipeline verified in " + (System.currentTimeMillis() - t0) + " ms (game not launched)");
    }
}
