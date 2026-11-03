package gothic3.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import gothic3.platform.Platform;
import gothic3.platform.RenderConfig;
import gothic3.platform.FrameBuffer;
import gothic3.platform.scene3d.Scene3D;
import gothic3.desktop.render3d.Renderer3D;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Presentation layer: uploads the latest published logical frame to a texture and draws it
 * nearest-neighbour, integer scaled, centred and letterboxed. Knows nothing about MIDP.
 * Each presented frame also signals the game thread ({@link gothic3.platform.FramePacer}) that the next one is wanted.
 */
public final class G3App extends ApplicationAdapter {
    private Runnable onReady;
    private SpriteBatch batch;
    private Texture tex;
    private Pixmap pm;
    private int[] argb = new int[0];
    private int[] rgba = new int[0];
    private int fw, fh;
    private long serial = -1;
    // ---- 3D view (docs/render3d.md) ----
    private Config cfg3d;
    private Renderer3D r3d;
    private gothic3.desktop.post2d.PostChain2D post2d;   // POST: 2D view effects (docs/post.md)
    private Scene3D scene;                   // scene of the latest uploaded frame (null = plain 2D frame)
    private final Scene3D[] sceneOut = new Scene3D[1];
    private volatile java.io.File pendingShot;
    private volatile java.util.concurrent.CountDownLatch shotLatch;
    private java.io.File windowFramesDir;
    private long windowFramesEveryMs;
    private long lastWindowShot, startMs = System.currentTimeMillis();

    // ---- overlay / statistics (render thread only, except overlayOn) ----
    private volatile boolean overlayOn;
    private BitmapFont font;
    private long statStartNs = System.nanoTime();
    private int statPresented, statUploaded;
    private long statTicks0, statFrames0;
    private double fpsPresented, fpsPublished, tickRate;
    private String perfLog;
    private java.io.PrintWriter perfOut;

    // ---- burst capture of presented frames ----
    private java.io.File burstDir;
    private int burstLeft;
    private long burstAfterTick;
    private final java.util.ArrayList<int[]> burstFrames = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> burstInfo = new java.util.ArrayList<>();
    private long burstT0;

    /** Debug: save the presented backbuffer (what the user sees) periodically. */
    public G3App windowFrames(java.io.File dir, long everyMs) {
        if (dir == null) return this;
        windowFramesDir = dir;
        windowFramesEveryMs = everyMs;
        dir.mkdirs();
        return this;
    }

    public G3App overlay(boolean on) { overlayOn = on; return this; }

    /** Enables the optional 3D renderer (created lazily on the GL thread). */
    public G3App view3d(Config cfg) { cfg3d = cfg; return this; }

    /** F5: switch between the 2D frame and the 3D diorama. */
    public void toggleView3d() {
        if (cfg3d == null) return;
        if (r3d != null && r3d.failed()) {
            System.err.println("[render3d] disabled after an error; restart to try again");
            return;
        }
        gothic3.platform.RenderConfig.toggleView3d();
        System.err.println("[render3d] view = " + (gothic3.platform.RenderConfig.view3d() ? "3d" : "2d"));
    }

    // ---- tile editor (F7, docs/render3d.md) ----
    private final gothic3.desktop.render3d.editor.TileEditor editor = new gothic3.desktop.render3d.editor.TileEditor();

    public gothic3.desktop.render3d.editor.TileEditor editor() { return editor; }

    /** F7: open / close the tile editor (switches the 3D view on if needed). Render thread. */
    public void toggleEditor() {
        if (cfg3d == null) return;
        if (!editor.isOpen() && !gothic3.platform.RenderConfig.view3d()) {
            toggleView3d();
            if (!gothic3.platform.RenderConfig.view3d()) return; // renderer disabled after an error
        }
        editor.toggle();
    }

    /** Automation (any thread): tile editor script command. */
    public void editorCommand(String line) {
        Gdx.app.postRunnable(() -> {
            try {
                editor.script(line);
            } catch (RuntimeException e) {
                System.err.println("[tile-editor] bad command '" + line + "': " + e);
            }
        });
    }

    /** F6: reload the tile table. */
    public void reload3d() {
        Gdx.app.postRunnable(() -> { if (r3d != null) r3d.reload(); }); // GL objects: render thread only
    }

    /** Automation (any thread): saves the presented window (backbuffer) as PNG; blocks until written or 10 s. */
    public void shotWindow(java.io.File f) {
        java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        shotLatch = l;
        pendingShot = f;
        try {
            l.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void toggleOverlay() { overlayOn = !overlayOn; }

    public boolean overlayVisible() { return overlayOn; }

    // ---- in-game options screen (F10, gothic3.desktop.options) ----
    private gothic3.desktop.options.OptionsOverlay options;

    private gothic3.desktop.byo.SetupScreen setup;

    /** First-run setup / build progress screen (bring-your-own-jar); null in the developer path. */
    public G3App setupScreen(gothic3.desktop.byo.SetupScreen s) { setup = s; return this; }

    public G3App optionsScreen(gothic3.desktop.options.OptionsOverlay o) { options = o; return this; }

    /** Options screen: re-read render3d.* and rebuild the 3D mesh (render thread). */
    public void applyRender3dSettings() {
        if (r3d != null) r3d.reloadSettings();
    }

    /** Options screen: re-read atmo.* only (no mesh rebuild). */
    public void applyAtmoSettings() {
        if (r3d != null) r3d.reloadAtmosphere();
    }

    public G3App perfLog(String target) { perfLog = target; return this; }

    /** Capture the next {@code count} presented frames (uploads) after {@code afterTick} logic ticks into {@code dir}. */
    public G3App burst(java.io.File dir, int count, long afterTick) {
        if (dir == null || count <= 0) return this;
        burstDir = dir;
        burstLeft = count;
        burstAfterTick = afterTick;
        dir.mkdirs();
        return this;
    }

    private void writeShot(java.io.File f) {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        byte[] px = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.getPixels().put(px).flip();
        for (int i = 3; i < px.length; i += 4) p.getPixels().put(i, (byte) 0xFF);
        com.badlogic.gdx.graphics.PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(f), p);
        p.dispose();
    }

    private void captureWindow() {
        long now = System.currentTimeMillis();
        if (now - lastWindowShot < windowFramesEveryMs) return;
        lastWindowShot = now;
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        byte[] px = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(0, 0, w, h, true); // flipY -> top-down
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.getPixels().put(px).flip();
        com.badlogic.gdx.graphics.PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(
                new java.io.File(windowFramesDir, String.format("window_%07d.png", now - startMs))), p);
        p.dispose();
    }

    public G3App(Runnable onReady) { this.onReady = onReady; }

    public G3App onReady(Runnable r) { onReady = r; return this; }

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        if (perfLog != null) {
            try {
                perfOut = "stderr".equals(perfLog) ? null : new java.io.PrintWriter(new java.io.FileWriter(perfLog), true);
            } catch (java.io.IOException e) {
                System.err.println("[perf] cannot open " + perfLog + ": " + e);
            }
        }
        if (onReady != null) onReady.run();
    }

    private void upload() {
        FrameBuffer fb = Platform.frames();
        long[] info = fb.info();
        if (info[0] == 0 || info[2] == serial) return;
        int w = (int) info[0], h = (int) info[1];
        if (w != fw || h != fh || tex == null) {
            if (tex != null) tex.dispose();
            if (pm != null) pm.dispose();
            fw = w;
            fh = h;
            pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            tex = new Texture(pm);
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            if (post2d != null) post2d.invalidate();
            argb = new int[w * h];
            rgba = new int[w * h];
        }
        sceneOut[0] = null;
        long s = fb.copyIfNewer(serial, argb, sceneOut);
        if (s == serial) return;
        serial = s;
        scene = sceneOut[0];
        statUploaded++;
        if (burstDir != null && burstLeft > 0 && RenderConfig.ticks() >= burstAfterTick) {
            if (burstFrames.isEmpty()) burstT0 = System.nanoTime();
            burstFrames.add(argb.clone());
            burstInfo.add(String.format("%d,%.3f,%d", serial, (System.nanoTime() - burstT0) / 1e6, RenderConfig.ticks()));
            if (--burstLeft == 0) writeBurst();
        }
        // ARGB (opaque) -> RGBA8888 big-endian ints
        if (scene != null) { // 3D mode: the frame is the HUD layer, transparent where the world shows through
            for (int i = 0; i < argb.length; i++) rgba[i] = (argb[i] << 8) | (argb[i] >>> 24);
        } else {
            for (int i = 0; i < argb.length; i++) rgba[i] = (argb[i] << 8) | 0xFF;
        }
        ByteBuffer bb = pm.getPixels();
        bb.clear();
        ByteOrder old = bb.order();
        bb.order(ByteOrder.BIG_ENDIAN);
        IntBuffer ib = bb.asIntBuffer();
        ib.put(rgba);
        bb.order(old);
        bb.position(0);
        tex.draw(pm, 0, 0);
    }

    private void writeBurst() {
        final java.util.ArrayList<int[]> frames = new java.util.ArrayList<>(burstFrames);
        final java.util.ArrayList<String> info = new java.util.ArrayList<>(burstInfo);
        final int w = fw, h = fh;
        final java.io.File dir = burstDir;
        burstFrames.clear();
        burstInfo.clear();
        Thread t = new Thread(() -> {
            try (java.io.PrintWriter csv = new java.io.PrintWriter(new java.io.File(dir, "burst.csv"))) {
                csv.println("frame,serial,ms,ticks");
                for (int i = 0; i < frames.size(); i++) {
                    java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    bi.setRGB(0, 0, w, h, frames.get(i), 0, w);
                    javax.imageio.ImageIO.write(bi, "png", new java.io.File(dir, String.format("burst_%04d.png", i)));
                    csv.println(i + "," + info.get(i));
                }
            } catch (Exception e) {
                System.err.println("[burst] " + e);
            }
            System.err.println("[burst] wrote " + frames.size() + " presented frames to " + dir);
        }, "burst-writer");
        t.start();
    }

    private void updateStats() {
        long now = System.nanoTime();
        long dt = now - statStartNs;
        if (dt < 1_000_000_000L) return;
        double sec = dt / 1e9;
        fpsPresented = statPresented / sec;
        fpsPublished = statUploaded / sec;
        long t = RenderConfig.ticks(), f = RenderConfig.framesRendered();
        tickRate = (t - statTicks0) / sec;
        double rendered = (f - statFrames0) / sec;
        if (perfLog != null) {
            String line = String.format("[perf] mode=%s presented=%.1f/s uploaded=%.1f/s game-frames=%.1f/s ticks=%.2f/s frame=%.2fms tick=%.2fms",
                    RenderConfig.interpolated() ? "interpolated" : "classic", fpsPresented, fpsPublished, rendered,
                    tickRate, RenderConfig.renderNanosAvg() / 1e6, RenderConfig.tickNanosAvg() / 1e6);
            if (perfOut != null) perfOut.println(line);
            else System.err.println(line);
        }
        statStartNs = now;
        statPresented = 0;
        statUploaded = 0;
        statTicks0 = t;
        statFrames0 = f;
    }

    /** Small ">> 3x" badge in the top right corner while fast forward is on (2D and 3D; presenter only). */
    private void drawSpeedIndicator(int bw, int bh) {
        if (font == null) return;
        String l = ">> " + RenderConfig.speed() + "x";
        float s = Math.max(1f, bh / 540f);
        font.getData().setScale(s);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        batch.begin();
        float x = bw - 8 * s - 44 * s, y = bh - 6 * s;
        font.setColor(0, 0, 0, 0.9f);
        font.draw(batch, l, x + 1, y - 1, 44 * s, com.badlogic.gdx.utils.Align.right, false);
        font.setColor(1f, 0.85f, 0.3f, 0.95f);
        font.draw(batch, l, x, y, 44 * s, com.badlogic.gdx.utils.Align.right, false);
        batch.end();
        font.getData().setScale(1f);
    }

    private void drawOverlay(int bw, int bh) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        batch.begin();
        String[] lines = {
            String.format("%s  present %.0f fps  game %.0f fps  logic %.1f Hz", RenderConfig.interpolated() ? "interpolated" : "classic",
                    fpsPresented, fpsPublished, tickRate),
            String.format("frame %.2f ms  tick %.2f ms  vsync %s  maxFps %d  (F3 hides)", RenderConfig.renderNanosAvg() / 1e6,
                    RenderConfig.tickNanosAvg() / 1e6, vsyncOn ? "on" : "off", RenderConfig.maxFps()),
            String.format("view %s%s  (F5 2D/3D, F6 reload tiles)", RenderConfig.view3d() ? "3D" : "2D",
                    r3d != null && RenderConfig.view3d() ? "  " + r3d.stats() : ""),
        };
        float y = bh - 6;
        for (String l : lines) {
            font.setColor(0, 0, 0, 1);
            font.draw(batch, l, 7, y - 1);
            font.setColor(1f, 1f, 0.4f, 1);
            font.draw(batch, l, 6, y);
            y -= 16;
        }
        batch.end();
    }

    /** Hotkey footer over the original game's settings menu (menu 2); presenter only, never reaches the game frame or its verification. */
    private void drawMenuHint(int bw, int bh, float x, float y, float dw, float dh) {
        if (font == null || !gothic3.platform.StartupPrefs.settingsMenuVisible.getAsBoolean()) return;
        boolean pad = false;
        try { pad = com.badlogic.gdx.controllers.Controllers.getControllers().size > 0; } catch (Throwable ignored) { }
        String l = pad ? "F5: 2D/3D   F8: fast forward   F3: status   F10 / Start+Back: advanced settings" : "F5: 2D/3D   F8: fast forward   F3: status   F10: advanced settings";
        float s = Math.max(0.9f, dh / fh * 0.62f);
        font.getData().setScale(s);
        float th = font.getLineHeight() + 10 * s;
        float by = Math.max(0, y) + dh * 0.085f; // inside the menu frame, clear of the bottom ornaments and of the window edge
        batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        batch.begin();
        batch.setColor(0, 0, 0, 0.9f);
        batch.draw(whitePixel(), x, by, dw, th);
        batch.setColor(0.9f, 0.75f, 0.3f, 1);
        batch.draw(whitePixel(), x, by + th - 2, dw, 2);
        batch.draw(whitePixel(), x, by, dw, 2);
        batch.setColor(1, 1, 1, 1);
        font.setColor(1f, 0.9f, 0.5f, 1);
        font.draw(batch, l, x, by + th - 5 * s, dw, com.badlogic.gdx.utils.Align.center, false);
        batch.end();
        font.getData().setScale(1f);
    }

    private com.badlogic.gdx.graphics.Texture white;
    private com.badlogic.gdx.graphics.Texture whitePixel() {
        if (white == null) {
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(1, 1, 1, 1);
            pm.fill();
            white = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
        return white;
    }

    private boolean drawn2dPost;
    private boolean vsyncOn = true;

    public G3App vsync(boolean on) { vsyncOn = on; return this; }

    @Override
    public void render() {
        Platform.pacer().frameWanted(); // vsync tick: let the game thread produce the next frame
        statPresented++;
        upload();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (tex == null) { // before the first game frame: the first-run setup / build progress screen
            int sw = Gdx.graphics.getBackBufferWidth(), sh = Gdx.graphics.getBackBufferHeight();
            if (setup != null && setup.active()) setup.render(sw, sh);
            serveShot();
            updateStats();
            return;
        }
        int bw = Gdx.graphics.getBackBufferWidth(), bh = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, bw, bh);
        int scale = Math.min(bw / fw, bh / fh);
        float dw, dh;
        if (scale >= 1) {
            dw = fw * scale;
            dh = fh * scale;
        } else { // window smaller than 1x: fit fractionally
            float f = Math.min(bw / (float) fw, bh / (float) fh);
            dw = fw * f;
            dh = fh * f;
        }
        float x = (int) ((bw - dw) / 2), y = (int) ((bh - dh) / 2);
        if (scene != null && cfg3d != null && gothic3.platform.RenderConfig.view3d()) {
            if (r3d == null) r3d = new Renderer3D(cfg3d);
            boolean bench = Bench.active();   // dev: glFinish-bracketed frame cost (script command bench)
            if (bench) {   // several renders per presented frame, each glFinish-bracketed: the first absorbs the swap / compositor wait
                for (int i = 0; i < Bench.BATCH && Bench.active(); i++) {
                    long t0 = Bench.begin();
                    r3d.render(scene, (int) x, (int) y, (int) dw, (int) dh);
                    Gdx.gl.glFinish();
                    Bench.end(t0, i > 0);
                }
            } else {
                r3d.render(scene, (int) x, (int) y, (int) dw, (int) dh);
            }
            Gdx.gl.glViewport(0, 0, bw, bh);
        }
        boolean plain2d = scene == null || cfg3d == null || !gothic3.platform.RenderConfig.view3d();
        if (plain2d && cfg3d != null) {   // POST: optional 2D effects (upscaler, bloom, grade, CRT) instead of the nearest blit
            if (post2d == null) post2d = new gothic3.desktop.post2d.PostChain2D(cfg3d);
            if (post2d.wanted() && post2d.draw(tex, fw, fh, (int) x, (int) y, (int) dw, (int) dh, (System.nanoTime() / 1000000L % 1000000000L) / 1000f)) {
                Gdx.gl.glViewport(0, 0, bw, bh);
                plain2d = false;
                drawn2dPost = true;
            } else if (post2d != null) {
                post2d.restoreFilter(tex);
            }
        }
        if (!drawn2dPost) {
            if (post2d != null) post2d.restoreFilter(tex);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
            batch.begin();
            batch.draw(tex, x, y, dw, dh, 0, 0, fw, fh, false, false);
            batch.end();
        }
        drawn2dPost = false;
        if (editor.isOpen()) {
            if (r3d != null && scene != null && gothic3.platform.RenderConfig.view3d() && !r3d.failed()) {
                try {
                    editor.render(r3d, bw, bh);
                } catch (RuntimeException e) {
                    System.err.println("[tile-editor] error, closing the editor: " + e);
                    e.printStackTrace();
                    editor.forceClose();
                }
            } else if (!gothic3.platform.RenderConfig.view3d()) {
                editor.forceClose();
            }
        }
        if (options == null || !options.isOpen()) drawMenuHint(bw, bh, x, y, dw, dh);
        if (options != null) options.render(bw, bh);
        updateStats();
        if (overlayOn) drawOverlay(bw, bh);
        if (RenderConfig.speed() > 1) drawSpeedIndicator(bw, bh);
        if (windowFramesDir != null) captureWindow();
        serveShot();
    }

    private void serveShot() {
        java.io.File shot = pendingShot;
        if (shot != null) {
            pendingShot = null;
            try { writeShot(shot); } catch (Exception e) { System.err.println("[shot] " + e); }
            java.util.concurrent.CountDownLatch l = shotLatch;
            if (l != null) l.countDown();
        }
    }

    @Override
    public void dispose() {
        editor.dispose();
        if (options != null) options.dispose();
        if (setup != null) setup.dispose();
        if (r3d != null) r3d.dispose();
        if (post2d != null) post2d.dispose();
        gothic3.desktop.render3d.post.Gfx.disposeShared();
        if (batch != null) batch.dispose();
        if (font != null) font.dispose();
        if (tex != null) tex.dispose();
        if (pm != null) pm.dispose();
    }
}
