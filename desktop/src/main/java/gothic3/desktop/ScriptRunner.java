package gothic3.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import gothic3.platform.RenderConfig;

/**
 * Scripted key input for unattended verification. One command per line:
 * <pre>
 *   # comment
 *   wait 2000            sleep (ms)
 *   press FIRE           key down (names: UP DOWN LEFT RIGHT FIRE SOFT_LEFT SOFT_RIGHT NUM0..NUM9 STAR POUND, or raw int)
 *   release FIRE
 *   tap DOWN [ms]        press, hold (default 150 ms), release, then wait 150 ms
 *   shot name            save the current logical frame as shot_name.png into --frames-dir
 *   waitticks N          wait until N more 10 Hz logic ticks completed (absolute schedule: no drift). Use with
 *                        --trace-ticks so that key events land on the same tick in every run
 *   tapticks K [hold [gap]]  press K, hold for `hold` ticks (default 2), release, wait `gap` ticks (default 1)
 *   view 2d|3d           switch the 3D view (like F5);  reload3d: reload the 3D tile table (F6)
 *   ed CMD...            tile editor (F7) test commands: open|close|mouse X Y|click [right]|cell L X Y|select L X Y|tile L ID|key [ctrl+]NAME|palette|save|info
 *   opt CMD...           options screen (F10) test commands: open|close|key NAME|pad NAME [up]|stick X Y|tab N|sel N|col N|mouse X Y|click|release|wheel N|get KEY|set KEY VALUE|state
 *   speed N              logic speed multiplier (1 = normal, fast forward = 2..8); ignored in deterministic runs
 *   wshot name           save the presented window (incl. overlays) as shot_name.png into --frames-dir
 *   bench N [label]      dev: glFinish-bracketed 3D frame cost over N presented frames (avg / p50 / p95 ms, uncapped fps) to stderr
 *   warp AREA X Y       dev only (-Dg3.debug=true): load a map at the next tick
 *   repl FILE            dev only: keep executing lines appended to FILE (this script's commands), until "exit"
 *   exit                 quit
 * </pre>
 */
public final class ScriptRunner implements Runnable {
    private final List<String> lines;
    private final InputState input;
    private final FrameDumper dumper;
    private final Runnable exit;
    private Runnable reload3d = () -> { };
    private java.util.function.Consumer<String> editor = l -> { };
    private java.util.function.Consumer<String> options = l -> { };

    public ScriptRunner(File f, InputState input, FrameDumper dumper, Runnable exit) throws Exception {
        this.lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        this.input = input;
        this.dumper = dumper;
        this.exit = exit;
    }

    public ScriptRunner reload3d(Runnable r) { reload3d = r; return this; }

    public ScriptRunner editor(java.util.function.Consumer<String> c) { editor = c; return this; }

    public ScriptRunner options(java.util.function.Consumer<String> c) { options = c; return this; }

    @Override
    public void run() {
        int n = 0;
        long cursor = startCursor; // absolute tick schedule (tick numbers start at 0 with the game) for waitticks/tapticks
        try {
            for (String raw : lines) {
                n++;
                String line = raw.trim();
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                switch (t[0].toLowerCase()) {
                    case "wait": Thread.sleep(Long.parseLong(t[1])); break;
                    case "press": input.press("script", MidpKey.parse(t[1])); break;
                    case "release": input.release("script", MidpKey.parse(t[1])); break;
                    case "tap": {
                        int code = MidpKey.parse(t[1]);
                        long hold = t.length > 2 ? Long.parseLong(t[2]) : 150;
                        input.press("script", code);
                        Thread.sleep(hold);
                        input.release("script", code);
                        Thread.sleep(150);
                        break;
                    }
                    case "waitticks": cursor += Long.parseLong(t[1]); waitTicks(cursor); break;
                    case "tapticks": {
                        int code = MidpKey.parse(t[1]);
                        long hold = t.length > 2 ? Long.parseLong(t[2]) : 2;
                        long gap = t.length > 3 ? Long.parseLong(t[3]) : 1;
                        input.press("script", code);
                        cursor += hold; waitTicks(cursor);
                        input.release("script", code);
                        cursor += gap; waitTicks(cursor);
                        break;
                    }
                    case "shot": if (dumper != null) dumper.shot(t.length > 1 ? t[1] : "shot" + n); break;
                    case "view": gothic3.platform.RenderConfig.setView3d(t[1].equalsIgnoreCase("3d")); break;
                    case "reload3d": reload3d.run(); break;
                    case "ed": editor.accept(line.substring(2).trim()); break;
                    case "opt": options.accept(line.substring(3).trim()); break;
                    case "wshot": if (dumper != null) dumper.windowShotNamed(t.length > 1 ? t[1] : "w" + n); break;
                    case "speed": gothic3.platform.RenderConfig.setSpeed(Integer.parseInt(t[1])); break;
                    case "bench": Bench.run(Integer.parseInt(t[1]), t.length > 2 ? t[2] : "bench"); break;
                    case "warp": gothic3.platform.RenderConfig.requestWarp(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])); break;
                    case "objstate": { // objstate AREA HEX: sets the object state bits of an area (door / chest open bits), takes effect when the area is loaded (dev, reflection)
                        java.lang.reflect.Field f = Class.forName("g3.GameEngine", true, gothic3.port.GameHooks.bridge().gameClassLoader()).getDeclaredField("objectStateMasks");
                        f.setAccessible(true);
                        int[] masks = (int[]) f.get(null);
                        if (masks != null) masks[Integer.parseInt(t[1])] = (int) Long.parseLong(t[2], 16);
                        else System.err.println("[script] objstate: no masks yet");
                        break;
                    }
                    case "repl": repl(new File(t[1]), cursor); break;
                    case "exit": exit.run(); return;
                    default: System.err.println("[script] line " + n + ": unknown command: " + line);
                }
            }
            System.err.println("[script] finished");
        } catch (Exception e) {
            System.err.println("[script] error at line " + n + ": " + e);
        }
    }

    /** Executes the lines appended to {@code f} (created if missing) as script commands; for interactive tuning sessions. */
    private void repl(File f, long cursor) throws Exception {
        if (!f.exists()) f.createNewFile();
        int done = 0;
        for (;;) {
            List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (l.size() > done) {
                List<String> batch = new java.util.ArrayList<>(l.subList(done, l.size()));
                done = l.size();
                java.io.File tmp = File.createTempFile("repl", ".script");
                Files.write(tmp.toPath(), batch, StandardCharsets.UTF_8);
                tmp.deleteOnExit();
                new ScriptRunner(tmp, input, dumper, exit).reload3d(reload3d).editor(editor).options(options).runBatch(RenderConfig.ticks());
                for (String s : batch) if (s.trim().equals("exit")) return;
            }
            Thread.sleep(200);
        }
    }

    private void runBatch(long cursor) {
        this.startCursor = cursor;
        run();
    }

    private long startCursor;

    private static void waitTicks(long target) {
        long left = target - RenderConfig.ticks();
        if (!RenderConfig.awaitTicks(target, Math.max(30000, left * 400))) {
            throw new IllegalStateException("timeout waiting for tick " + target + " (at " + RenderConfig.ticks() + ")");
        }
    }
}
