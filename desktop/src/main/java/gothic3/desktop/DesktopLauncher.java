package gothic3.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import gothic3.platform.Platform;
import gothic3.platform.RenderConfig;
import java.io.File;
import javax.microedition.midlet.MIDletLauncher;

public final class DesktopLauncher {
    private DesktopLauncher() {}

    private static volatile boolean shuttingDown;

    /** Named logical resolutions (docs/platform.md "Screen presets"). Unknown names fall back to wide. */
    static int[] parsePreset(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        switch (n) {
            case "classic": case "original": return new int[] {240, 320};
            case "tight": case "narrow": return new int[] {480, 270};
            case "": case "wide": case "default": return new int[] {640, 360};
            default:
        }
        int x = n.indexOf('x');
        if (x > 0) {
            try {
                int pw = Integer.parseInt(n.substring(0, x)), ph = Integer.parseInt(n.substring(x + 1));
                if (pw >= 64 && ph >= 64) return new int[] {pw, ph};
            } catch (NumberFormatException ignored) { }
        }
        System.err.println("[launcher] unknown screen preset '" + name + "', using wide (640x360)");
        return new int[] {640, 360};
    }

    /** window.scale: an integer, or "auto" = largest integer scale with the window within ~90% of the display. */
    public static int autoScale(String cfgValue, int w, int h) {
        try {
            return Math.max(1, Integer.parseInt(cfgValue.trim()));
        } catch (NumberFormatException ignored) { }
        int dw = 1920, dh = 1080;
        try {
            com.badlogic.gdx.Graphics.DisplayMode dm = Lwjgl3ApplicationConfiguration.getDisplayMode();
            dw = dm.width;
            dh = dm.height;
        } catch (Throwable ignored) { }
        return Math.max(1, Math.min(dw * 9 / 10 / w, dh * 9 / 10 / h));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Options opt = Options.parse(args);
        Config cfg = Config.load();

        // logical size: --width/--height > --view > config screen.width+height > config screen.preset (default wide 640x360)
        int[] preset = parsePreset(opt.view != null ? opt.view : cfg.get("screen.preset"));
        int cw = cfg.getInt("screen.width", -1), ch = cfg.getInt("screen.height", -1);
        if (opt.view == null && cw > 0 && ch > 0) preset = new int[] {cw, ch};
        int w = opt.width > 0 ? opt.width : preset[0];
        int h = opt.height > 0 ? opt.height : preset[1];
        Platform.setScreenSize(w, h);

        // ---- render mode / pacing (docs/platform.md "Render modes") ----
        String modeName = opt.renderMode != null ? opt.renderMode : cfg.get("render.mode");
        int mode = RenderConfig.parseMode(modeName.isEmpty() ? "interpolated" : modeName);
        int maxFps = opt.maxFps >= 0 ? opt.maxFps : Math.max(0, cfg.getInt("render.maxFps", 0));
        boolean vsync = opt.vsync != null ? opt.vsync.equalsIgnoreCase("on") || opt.vsync.equalsIgnoreCase("true") : cfg.getBool("window.vsync");
        RenderConfig.setMode(mode);
        String viewName = opt.renderView != null ? opt.renderView : cfg.get("render.view");
        RenderConfig.setView3d(viewName.equalsIgnoreCase("3d"));
        try {
            String[] cm = cfg.get("render3d.capture.margin").split("[,; ]+");
            if (cm.length == 4) RenderConfig.setCaptureMargin(Integer.parseInt(cm[0]), Integer.parseInt(cm[1]), Integer.parseInt(cm[2]), Integer.parseInt(cm[3]));
        } catch (NumberFormatException ignored) { }
        RenderConfig.setMaxFps(maxFps);
        Platform.pacer().setFollowDisplay(mode == RenderConfig.INTERPOLATED && maxFps == 0);
        if (opt.deterministic) {
            System.setProperty("g3.deterministic", "true");
            // saved games would make runs depend on earlier runs: use a fresh, empty RecordStore directory
            if (System.getProperty("g3.rms.dir") == null) {
                try {
                    Platform.setRmsDir(java.nio.file.Files.createTempDirectory("g3-det-rms").toFile());
                } catch (java.io.IOException e) {
                    System.err.println("[launcher] cannot create temp rms dir: " + e);
                }
            }
        }
        if (opt.noAudio) System.setProperty("g3.audio.off", "true");
        // startup questions (sound, 2D/3D): interactive launches only, so scripted / deterministic / verify runs keep the original flow tick for tick
        // (-Dg3.startup.force=true enables them anyway, for screenshots). --no-audio and the view flags always win over the saved answers.
        if (Boolean.getBoolean("g3.startup.force") || (!opt.deterministic && !opt.verify && opt.script == null && opt.traceTicks == null)) {
            gothic3.platform.StartupPrefs.active = true;
            gothic3.platform.StartupPrefs.done = cfg.getBool("firstRun.done");
            gothic3.platform.StartupPrefs.soundEnabled = !cfg.get("audio.enabled").equalsIgnoreCase("false");
            gothic3.platform.StartupPrefs.askGraphics = opt.renderView == null && !gothic3.platform.StartupPrefs.done; // an answered question is never asked again
            gothic3.platform.StartupPrefs.onSound = b -> { cfg.setAlways("audio.enabled", String.valueOf(b)); cfg.save(); };
            gothic3.platform.StartupPrefs.onGraphics = b -> {
                cfg.setAlways("render.view", b ? "3d" : "2d");
                RenderConfig.setView3d(b);
                cfg.save();
            };
            gothic3.platform.StartupPrefs.onFinished = () -> { if (opt.renderView != null) return; /* graphics not asked: keep asking later */ cfg.setAlways("firstRun.done", "true"); cfg.save(); };
        }
        if (opt.fixedSeed != null) System.setProperty("g3.fixedSeed", opt.fixedSeed);
        if (opt.traceTicks != null) System.setProperty("g3.trace.file", opt.traceTicks);
        if (opt.traceFields) System.setProperty("g3.trace.fields", "true");
        if (opt.verify) System.setProperty("g3.verify", "true");
        if (opt.dumpTicks != null) System.setProperty("g3.dump.ticks", opt.dumpTicks);
        if (opt.frameLog != null) System.setProperty("g3.frame.log", opt.frameLog);
        if (opt.dumpDir != null) System.setProperty("g3.dump.dir", opt.dumpDir);
        int scale = opt.scale > 0 ? opt.scale : autoScale(cfg.get("window.scale"), w, h);
        boolean fullscreen = opt.fullscreen || cfg.getBool("window.fullscreen");

        InputState input = new InputState();
        FrameDumper dumper = null;
        if (opt.framesDir != null) {
            dumper = new FrameDumper(new File(opt.framesDir), opt.frameEveryMs);
            Thread t = new Thread(dumper, "frame-dumper");
            t.setDaemon(true);
            t.start();
        }
        final FrameDumper fd = dumper;

        Runnable exit = () -> {
            if (shuttingDown) return;
            shuttingDown = true;
            if (Gdx.app != null) Gdx.app.postRunnable(() -> Gdx.app.exit());
            else System.exit(0);
        };
        Platform.setExitHandler(exit);

        // Bring your own jar (docs/byo-jar.md): the game is built at run time from the user's own copy of the original jar and loaded
        // from the cache; -Dg3.game.classpath=<game jar> (the gradle run task) skips all of that.
        final gothic3.desktop.byo.GameProvider provider = gothic3.desktop.byo.GameProvider.dev() ? null
                : new gothic3.desktop.byo.GameProvider(cfg, opt.jar, opt.rebuildGame);
        final gothic3.desktop.byo.SetupScreen setupScreen = provider == null ? null : new gothic3.desktop.byo.SetupScreen(provider, exit);

        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Gothic 3: The Beginning");
        c.setWindowedMode(w * scale, h * scale);
        c.setResizable(true);
        if (opt.hidden) c.setInitialVisible(false);
        c.setWindowSizeLimits(w, h, -1, -1);
        c.useVsync(vsync);
        // Classic: the presenter only needs to show 10 fps, 60 is plenty. Interpolated: vsync paces the presenter
        // (no extra cap); without vsync cap at max(maxFps, 60)/display rate so it does not spin.
        if (mode == RenderConfig.CLASSIC) c.setForegroundFPS(60);
        else if (vsync) c.setForegroundFPS(0);
        else c.setForegroundFPS(maxFps > 0 ? maxFps : Math.max(60, Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate));
        c.setIdleFPS(30);
        c.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0); // 3D view needs a depth buffer
        c.setInitialBackgroundColor(new com.badlogic.gdx.graphics.Color(0, 0, 0, 1));
        if (fullscreen) c.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        c.setWindowListener(new com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter() {
            @Override public boolean closeRequested() { return true; }
            @Override public void filesDropped(String[] files) { if (setupScreen != null) setupScreen.filesDropped(files); }
        });

        // render3d.* live preview for the options screen: the renderer re-reads this file when its timestamp changes
        // (the same mechanism as -Dg3.render3d.tune; a tune file the user passed on the command line is left alone)
        java.io.File ownTune = null;
        if (System.getProperty("g3.render3d.tune") == null) {
            try {
                ownTune = File.createTempFile("g3-render3d-options", ".properties");
                ownTune.deleteOnExit();
                System.setProperty("g3.render3d.tune", ownTune.getAbsolutePath());
            } catch (java.io.IOException e) {
                System.err.println("[launcher] cannot create the options tune file: " + e);
            }
        }
        final java.io.File tuneFile = ownTune;
        final gothic3.desktop.options.WindowControl win =
                new gothic3.desktop.options.WindowControl(w, h, scale, vsync, mode == RenderConfig.CLASSIC);
        Runnable toggleFs = win::toggleFullscreen;
        // audio level from the config (the options screen changes it at run time)
        javax.microedition.media.impl.PlayerFactory.setMasterVolume(
                cfg.getBool("audio.mute") ? 0f : Math.max(0, Math.min(100, cfg.getInt("audio.volume", 100))) / 100f);

        final G3App app = new G3App(null);
        Runnable onReady = () -> {
            InputHandler ih = new InputHandler(cfg, input, toggleFs).overlayToggle(app::toggleOverlay)
                    .view3dKeys(app::toggleView3d, app::reload3d).editorKeys(app::toggleEditor, app.editor());
            gothic3.desktop.options.OptionsOverlay oo = new gothic3.desktop.options.OptionsOverlay(cfg, (!opt.hidden && opt.script == null && opt.traceTicks == null) || Boolean.getBoolean("g3.options.hint"));
            gothic3.desktop.options.OptionsMenu om = new gothic3.desktop.options.OptionsMenu(cfg, app, ih, win, tuneFile, oo::close, () -> { cfg.save(); exit.run(); });
            oo.attach(om, ih);
            ih.optionsScreen(oo);
            app.optionsScreen(oo);
            java.util.function.Consumer<String> optionsScript = l -> Gdx.app.postRunnable(() -> {
                try { oo.script(l); } catch (RuntimeException e) { System.err.println("[options] bad command '" + l + "': " + e); }
            });
            Gdx.input.setInputProcessor(ih);
            ih.installControllers();
            ih.setupScreen(setupScreen);
            app.setupScreen(setupScreen);

            Runnable startGame = () -> {
                Thread game = new Thread(() -> {
                    try {
                        MIDletLauncher.launch(opt.midlet);
                    } catch (Throwable t) {
                        System.err.println("[launcher] MIDlet failed to start:");
                        t.printStackTrace();
                    }
                }, "MIDlet-start");
                game.start();
                FreezeWatchdog.start();
            };
            if (provider == null) startGame.run();
            else provider.start(startGame); // finds / builds the game, then runs startGame on its own thread

            if (opt.script != null) {
                try {
                    Thread st = new Thread(new ScriptRunner(new File(opt.script), input, fd, exit).reload3d(app::reload3d).editor(app::editorCommand).options(optionsScript), "script");
                    st.setDaemon(true);
                    st.start();
                } catch (Exception e) {
                    System.err.println("[launcher] cannot read script: " + e);
                }
            }
            if (opt.exitAfterTicks >= 0) {
                Thread et = new Thread(() -> {
                    RenderConfig.awaitTicks(opt.exitAfterTicks, Long.MAX_VALUE / 4_000_000L);
                    if (fd != null) fd.shot("final");
                    exit.run();
                }, "exit-after-ticks");
                et.setDaemon(true);
                et.start();
            }
            if (opt.exitAfterMs >= 0) {
                Thread et = new Thread(() -> {
                    try { Thread.sleep(opt.exitAfterMs); } catch (InterruptedException ignored) { }
                    if (fd != null) fd.shot("final");
                    exit.run();
                }, "exit-after");
                et.setDaemon(true);
                et.start();
            }
        };

        if (fd != null) fd.windowShot(app::shotWindow);
        app.view3d(cfg).onReady(onReady).vsync(vsync).overlay(opt.overlay || cfg.getBool("debug.overlay")).perfLog(opt.perfLog)
                .burst(opt.burstDir != null ? new File(opt.burstDir) : null, opt.burstCount, opt.burstAfterTick)
                .windowFrames(opt.windowFrames && opt.framesDir != null ? new File(opt.framesDir, "window") : null, opt.frameEveryMs);
        new Lwjgl3Application(app, c);
        System.exit(0);
    }
}
