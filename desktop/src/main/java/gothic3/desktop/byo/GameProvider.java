package gothic3.desktop.byo;

import gothic3.buildtools.GameBuild;
import gothic3.buildtools.GamePipeline;
import gothic3.buildtools.JarCheck;
import gothic3.desktop.Config;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bring-your-own-jar: finds the user's game jar (--jar, remembered path, auto-scan, or the setup screen), verifies it, builds the
 * game jar in-process (decompile + patch + compile, see {@link GameBuild}) into a per-user cache and hands it to the launcher.
 * Runs on its own thread; the setup screen only reads the volatile state. docs/byo-jar.md.
 */
public final class GameProvider {
    public enum State { SCANNING, NEED_JAR, BUILDING, NOTICE, READY }

    /** Stages shown by the progress screen; GamePipeline stage names map onto them (never backwards). */
    public static final String[] STAGES = {"Verifying the jar", "Decompiling", "Patching", "Converting audio", "Compiling"};

    /** Set by the launcher when the game comes from -Dg3.game.classpath (developer path): no setup at all. */
    public static boolean dev() {
        String p = System.getProperty("g3.game.classpath");
        return p != null && !p.isEmpty();
    }

    /** The provider of this run (for the options menu action); null in dev mode. */
    public static volatile GameProvider current;

    public volatile State state = State.SCANNING;
    /** Multi-line problem text for the user (wrong jar, build failure), or null. */
    public volatile String error;
    public volatile String notice;
    public volatile int stage = -1;
    public volatile String detail = "";
    public volatile long stageStartMs, buildStartMs;
    public volatile String jarName = "";
    public final List<String> searched = new ArrayList<>();
    public volatile Properties props = new Properties();

    private final Config cfg;
    private final String argJar;
    private final boolean forceRebuild;
    private final BlockingQueue<Object> commands = new LinkedBlockingQueue<>();
    private Runnable launch;
    private volatile Path gameJar;
    private Path cacheDir;

    private static final Object RESCAN = new Object();

    public GameProvider(Config cfg, String argJar, boolean forceRebuild) {
        this.cfg = cfg;
        this.argJar = argJar;
        this.forceRebuild = forceRebuild;
        current = this;
    }

    public boolean active() { return state != State.READY; }

    /** Starts the worker; {@code launch} runs on it once -Dg3.game.classpath is set to the built game jar. */
    public void start(Runnable launch) {
        this.launch = launch;
        Thread t = new Thread(this::run, "game-setup");
        t.setDaemon(true);
        t.start();
    }

    /** The user chose / dropped a file. */
    public void choose(Path p) { commands.add(p); }

    public void rescan() { commands.add(RESCAN); }

    // ------------------------------------------------------------------------------------------------ worker

    private void run() {
        ByoBundle bundle;
        try {
            bundle = new ByoBundle();
            props = bundle.buildProperties();
        } catch (Exception e) {
            fatal(e.getMessage());
            return;
        }
        boolean rebuild = forceRebuild;
        Path pending = argJar != null ? Paths.get(argJar) : null;
        boolean explicit = pending != null;
        for (;;) {
            try {
                if (pending == null) {
                    state = State.SCANNING;
                    pending = findJar();
                    if (pending == null) {
                        state = State.NEED_JAR;
                        Object c = commands.take();
                        if (c instanceof Path) { pending = (Path) c; error = null; notice = null; }
                        else error = null;
                        continue;
                    }
                }
                JarCheck.Result jr = JarCheck.inspect(pending, props);
                if (jr.kind != JarCheck.Kind.OK) {
                    error = pending + "\n" + jr.message();
                    System.err.println("[setup] " + error.replace("\n", "\n[setup] "));
                    pending = null;
                    state = State.NEED_JAR;
                    explicit = false;
                    Object c = commands.take();
                    if (c instanceof Path) { pending = (Path) c; error = null; }
                    else { error = null; }
                    continue;
                }
                error = null;
                jarName = pending.getFileName().toString();
                if (build(bundle, pending, jr, rebuild)) {
                    remember(pending);
                    state = State.READY;
                    System.setProperty("g3.game.classpath", gameJar.toString());
                    launch.run();
                    return;
                }
                // build failed: error is set
                pending = null;
                rebuild = true;
                state = State.NEED_JAR;
                Object c = commands.take();
                if (c instanceof Path) pending = (Path) c;
                error = null;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                error = "Unexpected error: " + t;
                pending = null;
                state = State.NEED_JAR;
            }
        }
    }

    private void fatal(String msg) {
        error = msg;
        System.err.println("[setup] " + msg);
        state = State.NEED_JAR;
    }

    private Path findJar() {
        searched.clear();
        String remembered = cfg.get("game.jar");
        if (!remembered.isEmpty()) {
            Path p = Paths.get(remembered);
            if (Files.isRegularFile(p) && JarCheck.inspect(p, props).kind == JarCheck.Kind.OK) return p;
            notice = "The remembered jar (" + remembered + ") is gone or has changed; searching again.";
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(Paths.get("").toAbsolutePath());
        try {
            File self = new File(GameProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            dirs.add((self.isFile() ? self.getParentFile() : self).toPath());
        } catch (Exception ignored) { }
        Path home = Paths.get(System.getProperty("user.home"));
        dirs.add(home.resolve("Downloads"));
        dirs.add(home.resolve("Desktop"));
        dirs.add(home);
        List<Path> seen = new ArrayList<>();
        for (Path d : dirs) {
            d = d.toAbsolutePath().normalize();
            if (seen.contains(d)) continue;
            seen.add(d);
            searched.add(d.toString());
            List<Path> jars = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.list(d)) {
                s.filter(p -> JarCheck.plausible(p, props)).sorted().forEach(jars::add);
            } catch (Exception ignored) { continue; }
            for (Path j : jars) {
                if (JarCheck.inspect(j, props).kind == JarCheck.Kind.OK) {
                    System.err.println("[setup] found " + j);
                    return j;
                }
            }
        }
        return null;
    }

    private void remember(Path jar) {
        String abs = jar.toAbsolutePath().toString();
        if (!abs.equals(cfg.get("game.jar"))) {
            cfg.setAlways("game.jar", abs);
            cfg.save();
        }
    }

    /** @return true when gameJar is ready; false with {@link #error} set otherwise. */
    private boolean build(ByoBundle bundle, Path jar, JarCheck.Result jr, boolean rebuild) {
        cacheDir = new File(Config.dataDir(), "game/" + jr.sha.substring(0, 12) + "-" + bundle.hash().substring(0, 12)).toPath();
        Path out = cacheDir.resolve("game.jar");
        try {
            if (rebuild) GamePipeline.deleteTree(cacheDir);
            if (Files.isRegularFile(out)) {
                gameJar = out;
                System.err.println("[setup] using cached game " + out);
                return true;
            }
            state = State.BUILDING;
            buildStartMs = System.currentTimeMillis();
            stage = 0;
            stageStartMs = buildStartMs;
            Path tmp = Files.createTempDirectory("g3-byo");
            try {
                bundle.extractTo(tmp);
                GameBuild.Result r = GameBuild.build(tmp, jar, out, System.getProperty("java.class.path"), GamePipeline.Audio.OPTIONAL, this::progress);
                if (!r.audioConverted) {
                    Files.writeString(cacheDir.resolve("no-audio"), "ffmpeg was not available when this game was built\n");
                    notice = "No sound effects: the bundled ffmpeg is missing or did not start. Install ffmpeg, then open F10 > Game > Rebuild game data. (Music is unaffected.)";
                    state = State.NOTICE;
                    Thread.sleep(3500);
                }
            } finally {
                GamePipeline.deleteTree(tmp);
            }
            System.err.println("[setup] built " + out + " in " + (System.currentTimeMillis() - buildStartMs) + " ms");
            pruneOldCaches();
            gameJar = out;
            return true;
        } catch (GamePipeline.Failure f) {
            error = f.getMessage();
        } catch (Exception e) {
            e.printStackTrace();
            error = "Building the game failed: " + e;
        }
        System.err.println("[setup] " + error.replace("\n", "\n[setup] "));
        try { GamePipeline.deleteTree(cacheDir); } catch (Exception ignored) { }
        return false;
    }

    private void pruneOldCaches() {
        File[] all = cacheDir.getParent().toFile().listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f.isDirectory() && !f.equals(cacheDir.toFile())) {
                try { GamePipeline.deleteTree(f.toPath()); } catch (Exception ignored) { }
            }
        }
    }

    private void progress(String st, String d) {
        int idx;
        switch (st) {
            case "verifying": idx = 0; break;
            case "decompiling": idx = 1; break;
            case "patching": idx = 2; break;
            case "extracting": case "converting audio": idx = 3; break;
            default: idx = 4; // compiling, packaging
        }
        long now = System.currentTimeMillis();
        if (idx > stage) { stage = idx; stageStartMs = now; }
        detail = d;
        System.err.println("[setup] +" + (now - buildStartMs) + " ms " + st + ": " + d);
    }

    // ------------------------------------------------------------------------------------- actions (any thread)

    /** F10 > Game > Rebuild game data: forget the built game; it is rebuilt at the next start. */
    public boolean invalidateCache() {
        try {
            if (gameJar == null) return false;
            GamePipeline.deleteTree(gameJar.getParent());
            return true;
        } catch (Exception e) {
            System.err.println("[setup] cannot delete the cache: " + e);
            return false;
        }
    }

    public static Path cacheRoot() { return new File(Config.dataDir(), "game").toPath(); }

    /** Native "open file" dialog on a helper thread (blocking calls must not run on the render thread). */
    public void openFileDialog() {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                String start = Paths.get(System.getProperty("user.home"), "Downloads").toFile().isDirectory()
                        ? Paths.get(System.getProperty("user.home"), "Downloads").toString() : System.getProperty("user.home");
                String f = FileDialog.pickJar(start + File.separator);
                if (f != null && !f.isEmpty()) { notice = null; choose(Paths.get(f)); }
                else if (System.currentTimeMillis() - t0 < 400)
                    notice = "No native file dialog could be opened (on Linux it needs zenity or kdialog). Drop the .jar onto this window, or start with --jar <path>.";
            } catch (Throwable e) {
                notice = "No native file dialog available (" + e.getClass().getSimpleName() + "). Drop the .jar onto this window, or start with --jar <path>.";
            }
        }, "file-dialog");
        t.setDaemon(true);
        t.start();
    }
}
