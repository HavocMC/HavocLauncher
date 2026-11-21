# HavocLauncher

Standalone desktop launcher for **HavocClient** — the "Lunar-style" experience for the Havoc SMP:
double-click the exe, press PLAY, and it installs and starts Minecraft 26.3 with Fabric + HavocClient. No manual jar dragging, no Fabric Installer, no Java setup.

## How it works

```
HavocLauncher.exe (Windows installer, built with jpackage)
   |  1. ensures a Java 25 runtime (downloads Temurin JRE on first run)
   |  2. signs you in (Microsoft device-code login, or offline username)
   |  3. downloads Minecraft 26.3 + libraries (+ reuses .minecraft assets if present)
   |  4. merges the Fabric Loader profile (KnotClient) into the vanilla version
   |  5. installs bundled mods: havocclient + fabric-api  ->  <dir>/mods/
   |  6. launches the game and streams its log into the launcher window
```

Everything lives in its own folder, like Lunar's:
- Windows: `%APPDATA%\.havoclauncher`
- Linux: `~/.havoclauncher`
- macOS: `~/Library/Application Support/havoclauncher`

The vanilla launcher and its accounts are never touched. If the official launcher is installed, its downloaded assets are **reused** (saves ~600 MB on first install).

## For players

1. Grab the latest `HavocLauncher-<version>.exe` from [Releases](../../releases) and run it.
2. First PLAY: launcher downloads Java 25 + Minecraft (~150 MB, one time).
3. Press **PLAY**. Keystrokes/HUD/Zoom/Click GUI are all inside — Right Shift opens the Click GUI, hold C to zoom.

Offline mode: type a username and click "Use offline" — works for singleplayer and offline-mode servers only.

## Microsoft login setup (one time, for the server owner)

Real multiplayer needs a real Microsoft account login. The launcher uses the standard
**OAuth device-code flow** and needs its own Azure app id (free):

1. Go to https://portal.azure.com -> **Microsoft Entra ID** -> **App registrations** -> **New registration**.
2. Name: `HavocLauncher`. Supported accounts: **"Personal Microsoft accounts only"** (or the "any org + personal" option).
3. No redirect URI needed. Under **Authentication** -> **Advanced settings** -> set **"Allow public client flows" = Yes**.
4. Copy the **Application (client) ID**.
5. Put it into `%APPDATA%\.havoclauncher\launcher.properties`:
   ```properties
   client_id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   ```
6. Restart the launcher -> **Login with Microsoft** shows a code, you open `microsoft.com/link`, done. The session is cached until it expires (auto-refresh).

## Building from source

```bash
./gradlew jar            # -> build/libs/havoclauncher-<v>.jar (fat jar, Java 21+ to run)
```

Run the jar directly: `java -jar havoclauncher-<v>.jar`

Headless pipeline test (downloads everything except asset objects, prints the launch command, does not start the game):

```bash
java -jar havoclauncher-<v>.jar --test-prepare
```

## Windows exe

`.github/workflows/windows-exe.yml` builds `HavocLauncher-<v>.exe` with `jpackage` on every `v*` tag and attaches it to the GitHub release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The exe is a real Windows installer (Start-menu shortcut, optional directory chooser) and bundles its own Java runtime — players need nothing installed.

## Settings (`launcher.properties`)

| key | default | meaning |
|---|---|---|
| `client_id` | *(empty)* | Azure app id for Microsoft login (blank = login button shows setup help) |
| `ram_gb` | `2` | game memory (also selectable in the UI) |
| `width` / `height` | `854` / `480` | custom window size (used when `custom_resolution=true`) |
| `keep_launcher_open` | `true` | keep the launcher window while the game runs |

## Updating HavocClient

Both mods (`havocclient`, `fabric-api`) are bundled inside the launcher under `src/main/resources/mods/` and are re-copied into `<dir>/mods/` on every launch. To ship a new client build: drop the new jar there, bump `version` in `build.gradle`, tag `v0.x.y` — the workflow produces the new exe.

## Roadmap

- v0.2: remote mod fetch (launcher auto-downloads the latest HavocClient release instead of bundling) + launcher self-update
- v0.3: server list management (join `play.havoc...` directly from the launcher), skins cape
- v0.4: per-version channels (beta / release), crash reports upload

## Disclaimer

HavocLauncher is an independent community project. It is **not** affiliated with or endorsed by Mojang or Microsoft. It downloads Minecraft exclusively from official Mojang endpoints; players must own a genuine Minecraft account for online play.
