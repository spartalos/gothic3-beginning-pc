package gothic3.desktop.byo;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * First-run setup / build progress screen (same look as the F10 options screen). Shown instead of the game until the game jar is
 * built: asks for the user's own jar when none was found, names a wrong build, then shows the build stages.
 * Render thread only; keyboard, gamepad and mouse; the logic lives in {@link GameProvider}.
 */
public final class SetupScreen {
    private static final Color BG = new Color(0.04f, 0.05f, 0.08f, 1f);
    private static final Color SEL = new Color(0.20f, 0.32f, 0.55f, 0.85f);
    private static final Color ACCENT = new Color(0.40f, 0.70f, 1f, 1f);
    private static final Color TEXT = new Color(0.93f, 0.93f, 0.95f, 1f);
    private static final Color DIM = new Color(0.58f, 0.60f, 0.66f, 1f);
    private static final Color WARN = new Color(1f, 0.72f, 0.28f, 1f);
    private static final Color BAD = new Color(1f, 0.45f, 0.40f, 1f);
    private static final Color GOOD = new Color(0.55f, 0.90f, 0.55f, 1f);
    private static final Color FIELD = new Color(1f, 1f, 1f, 0.09f);

    private final GameProvider gp;
    private final Runnable quit;
    private BitmapFont font;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private final GlyphLayout gl = new GlyphLayout();
    private final Matrix4 proj = new Matrix4();
    private final List<float[]> rects = new ArrayList<>();
    private final List<Object[]> texts = new ArrayList<>();
    private float bw, bh, ui;
    private int sel;
    private float[][] btn = new float[0][];
    private boolean wasDown;
    private int stickDir;
    private static final String[] BUTTONS = {"Choose jar...", "Search again", "Quit"};

    public SetupScreen(GameProvider gp, Runnable quit) {
        this.gp = gp;
        this.quit = quit;
    }

    public boolean active() { return gp.active(); }

    // ---------------------------------------------------------------------------------------------- input

    private boolean askingForJar() { return gp.state == GameProvider.State.NEED_JAR; }

    public void keyDown(int k) {
        switch (k) {
            case Input.Keys.UP: case Input.Keys.LEFT: nav(-1); break;
            case Input.Keys.DOWN: case Input.Keys.RIGHT: case Input.Keys.TAB: nav(1); break;
            case Input.Keys.ENTER: case Input.Keys.NUMPAD_ENTER: case Input.Keys.SPACE: activate(sel); break;
            case Input.Keys.ESCAPE: quit.run(); break;
            default:
        }
    }

    public void padButton(String name, boolean down) {
        if (!down) return;
        switch (name) {
            case "DPAD_UP": case "DPAD_LEFT": nav(-1); break;
            case "DPAD_DOWN": case "DPAD_RIGHT": nav(1); break;
            case "A": activate(sel); break;
            case "B": quit.run(); break;
            default:
        }
    }

    /** Left stick, already thresholded to -1/0/1 per axis. */
    public void padStick(int x, int y) {
        int d = y != 0 ? y : x;
        if (d != 0 && d != stickDir) nav(d);
        stickDir = d;
    }

    private void nav(int d) {
        if (!askingForJar()) return;
        sel = (sel + d + BUTTONS.length) % BUTTONS.length;
    }

    private void activate(int i) {
        if (!askingForJar()) return;
        switch (i) {
            case 0: gp.openFileDialog(); break;
            case 1: gp.rescan(); break;
            default: quit.run();
        }
    }

    /** Window drop: the first .jar is taken. */
    public void filesDropped(String[] files) {
        if (files == null || !askingForJar()) return;
        for (String f : files) {
            if (f.toLowerCase().endsWith(".jar")) { gp.choose(java.nio.file.Paths.get(f)); return; }
        }
        gp.notice = "That is not a .jar file.";
    }

    // ---------------------------------------------------------------------------------------------- drawing

    private void init() {
        font = new BitmapFont(true);
        font.getRegion().getTexture().setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
    }

    public void dispose() {
        if (font != null) font.dispose();
        if (batch != null) batch.dispose();
        if (shapes != null) shapes.dispose();
        font = null;
    }

    private void rect(float x, float y, float w, float h, Color c) { rects.add(new float[] {x, y, w, h, c.r, c.g, c.b, c.a}); }

    /** Draws wrapped text at (x,y) with the given width; returns the height used. align: 0 left, 1 centre. */
    private float text(String s, float x, float y, float width, float scale, Color c, int align) {
        font.getData().setScale(1.12f * ui * scale);
        gl.setText(font, s, c, width, align == 1 ? Align.center : Align.left, true);
        texts.add(new Object[] {s, x, y, width, scale, c, align});
        return gl.height;
    }

    public void render(int backW, int backH) {
        bw = backW;
        bh = backH;
        if (font == null) init();
        ui = Math.max(0.8f, Math.min(3f, bh / 720f));
        rects.clear();
        texts.clear();
        compose();
        handleMouse();
        flush();
    }

    private void compose() {
        rect(0, 0, bw, bh, BG);
        float pw = Math.min(bw * 0.94f, 800 * ui);
        float x = (bw - pw) / 2, pad = 18 * ui;
        float y = Math.max(14 * ui, bh * 0.04f);
        float w = pw - 2 * pad;
        rect(x, y - 6 * ui, pw, 3 * ui, ACCENT);
        y += 6 * ui;
        text("GOTHIC 3: THE BEGINNING", x + pad, y, w, 1.5f, ACCENT, 0);
        y += lineH(1.5f) + 2 * ui;
        GameProvider.State st = gp.state;
        if (st == GameProvider.State.BUILDING || st == GameProvider.State.NOTICE || st == GameProvider.State.READY) buildingView(x + pad, y, w);
        else setupView(x + pad, y, w, st);
    }

    private float lineH(float scale) {
        font.getData().setScale(1.12f * ui * scale);
        return font.getLineHeight();
    }

    private void setupView(float x, float y, float w, GameProvider.State st) {
        Properties p = gp.props;
        y += text("First-time setup", x, y, w, 1.0f, TEXT, 0) + 10 * ui;
        if (st == GameProvider.State.SCANNING) {
            y += text("Looking for your game jar...", x, y, w, 1f, DIM, 0);
            return;
        }
        y += text("This port does not contain the game. It builds it on this computer from your own copy of the original mobile game"
                + " (a .jar file). Nothing is uploaded; the result is kept in your user data folder.", x, y, w, 0.9f, DIM, 0) + 12 * ui;

        String name = p.getProperty("jar.name", "Gothic3-TheBeginning_s40v3a.jar");
        long size = Long.parseLong(p.getProperty("jar.size", "0"));
        String sha = p.getProperty("jar.sha256", "");
        float boxY = y, bx = x + 10 * ui, bwid = w - 20 * ui;
        float h = 0;
        y += 8 * ui;
        h += text("Please select this file:", bx, y + h, bwid, 0.9f, DIM, 0) + 4 * ui;
        h += text(name, bx, y + h, bwid, 1.1f, GOOD, 0) + 4 * ui;
        h += text(p.getProperty("jar.description", "") + ", " + String.format(java.util.Locale.ROOT, "%,d", size) + " bytes", bx, y + h, bwid, 0.9f, TEXT, 0) + 2 * ui;
        h += text("SHA-256 " + (sha.length() > 16 ? sha.substring(0, 8) + "..." + sha.substring(sha.length() - 4) : sha), bx, y + h, bwid, 0.9f, TEXT, 0) + 6 * ui;
        h += text("Use your own copy, for example from a phone backup. Other builds (such as the 452x416 or the multiscreen version) do not work.",
                bx, y + h, bwid, 0.85f, DIM, 0) + 4 * ui;
        rect(x, boxY, w, h + 16 * ui, FIELD);
        y = boxY + h + 16 * ui + 10 * ui;

        String err = gp.error;
        if (err != null) {
            float eh = text(err, x + 10 * ui, y + 6 * ui, w - 20 * ui, 0.85f, BAD, 0);
            rect(x, y, w, eh + 12 * ui, new Color(0.45f, 0.12f, 0.10f, 0.45f));
            rect(x, y, 3 * ui, eh + 12 * ui, BAD);
            y += eh + 12 * ui + 10 * ui;
        }
        String note = gp.notice;
        if (note != null) y += text(note, x, y, w, 0.85f, WARN, 0) + 8 * ui;

        // buttons
        float bhh = Math.round(lineH(1f) * 1.7f), gap = 14 * ui;
        float bwi = Math.round((w - 2 * gap) / 3f);
        btn = new float[BUTTONS.length][];
        for (int i = 0; i < BUTTONS.length; i++) {
            float bxx = x + i * (bwi + gap);
            btn[i] = new float[] {bxx, y, bwi, bhh};
            rect(bxx, y, bwi, bhh, i == sel ? SEL : FIELD);
            if (i == sel) rect(bxx, y + bhh - 3 * ui, bwi, 3 * ui, ACCENT);
            text(BUTTONS[i], bxx + bwi / 2, y + (bhh - lineH(1f)) / 2 + 3 * ui, bwi, 1f, TEXT, 1);
        }
        y += bhh + 12 * ui;
        y += text("Up/Down + Enter (gamepad: D-pad + A)   |   or drop the .jar onto this window   |   or start with --jar <path>",
                x, y, w, 0.8f, DIM, 0) + 12 * ui;
        if (!gp.searched.isEmpty() && err == null) {
            StringBuilder sb = new StringBuilder("No copy found in: ");
            for (int i = 0; i < gp.searched.size(); i++) sb.append(i > 0 ? ", " : "").append(gp.searched.get(i));
            text(sb.toString(), x, y, w, 0.75f, DIM, 0);
        }
    }

    private void buildingView(float x, float y, float w) {
        btn = new float[0][];
        y += text("Preparing the game", x, y, w, 1.0f, TEXT, 0) + 4 * ui;
        y += text("from " + gp.jarName + " - this happens once, later starts are instant.", x, y, w, 0.9f, DIM, 0) + 16 * ui;
        int cur = gp.state == GameProvider.State.BUILDING ? gp.stage : GameProvider.STAGES.length;
        long now = System.currentTimeMillis();
        for (int i = 0; i < GameProvider.STAGES.length; i++) {
            Color c = i < cur ? GOOD : i == cur ? ACCENT : DIM;
            String mark = i < cur ? "done" : i == cur ? "..." : "";
            rect(x, y + lineH(1f) * 0.15f, 4 * ui, lineH(1f) * 0.7f, c);
            text(GameProvider.STAGES[i], x + 16 * ui, y, w, 1f, i == cur ? TEXT : c, 0);
            text(i == cur ? gp.detail + "  " + (now - gp.stageStartMs) / 1000 + " s" : mark, x + w, y + lineH(1f) * 0.1f, w, 0.85f, c, 2);
            y += lineH(1f) * 1.5f;
        }
        y += 14 * ui;
        if (gp.notice != null) y += text(gp.notice, x, y, w, 0.9f, WARN, 0) + 6 * ui;
        if (gp.state == GameProvider.State.READY) text("Starting the game...", x, y, w, 1f, ACCENT, 0);
        // a moving bar so a long step does not look frozen
        float t = (now % 1600) / 1600f;
        rect(x, bh - 30 * ui, w, 3 * ui, FIELD);
        rect(x + (w * 0.8f) * t, bh - 30 * ui, w * 0.2f, 3 * ui, ACCENT);
    }

    private void handleMouse() {
        if (!askingForJar() || btn.length == 0 || Gdx.input == null) return;
        float mx = Gdx.input.getX() * bw / Math.max(1f, Gdx.graphics.getWidth());
        float my = Gdx.input.getY() * bh / Math.max(1f, Gdx.graphics.getHeight());
        boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        for (int i = 0; i < btn.length; i++) {
            float[] r = btn[i];
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT) || (down && !wasDown)) { sel = i; activate(i); }
                else if (!down && Gdx.input.getDeltaX() + Gdx.input.getDeltaY() != 0) sel = i;
            }
        }
        wasDown = down;
    }

    private void flush() {
        Gdx.gl.glViewport(0, 0, (int) bw, (int) bh);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        proj.setToOrtho2D(0, bh, bw, -bh);
        shapes.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (float[] r : rects) {
            shapes.setColor(r[4], r[5], r[6], r[7]);
            shapes.rect(r[0], r[1], r[2], r[3]);
        }
        shapes.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.begin();
        for (Object[] t : texts) {
            String s = (String) t[0];
            float x = (Float) t[1], y = (Float) t[2], width = (Float) t[3], scale = (Float) t[4];
            int align = (Integer) t[6];
            Color c = (Color) t[5];
            font.getData().setScale(1.12f * ui * scale);
            float dx = Math.max(1, ui * 0.8f);
            if (align == 2) { // right aligned: x is the right edge
                gl.setText(font, s);
                font.setColor(0, 0, 0, c.a * 0.6f);
                font.draw(batch, s, x - gl.width + dx, y + dx);
                font.setColor(c);
                font.draw(batch, s, x - gl.width, y);
                continue;
            }
            float left = align == 1 ? x - width / 2 : x;
            int ha = align == 1 ? Align.center : Align.left;
            font.setColor(0, 0, 0, c.a * 0.6f);
            font.draw(batch, s, left + dx, y + dx, width, ha, true);
            font.setColor(c);
            font.draw(batch, s, left, y, width, ha, true);
        }
        batch.end();
        font.getData().setScale(1f);
    }
}
