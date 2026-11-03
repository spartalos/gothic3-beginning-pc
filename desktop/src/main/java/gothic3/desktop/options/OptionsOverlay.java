package gothic3.desktop.options;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import gothic3.desktop.Config;
import gothic3.desktop.InputHandler;
import gothic3.platform.RenderConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game options screen (F10, or Start+Back on a gamepad). A libGDX screen-space panel on the left of the window drawn over
 * the game frame; while it is open the game is paused ({@link RenderConfig#setPaused}) and all input goes here.
 * Render / input thread only. Content and behaviour of the items: {@link OptionsMenu}.
 */
public final class OptionsOverlay {
    private enum Modal { NONE, CAPTURE, CONFLICT, QUIT }

    private static final Color BG = new Color(0.04f, 0.05f, 0.08f, 0.90f);
    private static final Color SEL = new Color(0.20f, 0.32f, 0.55f, 0.65f);
    private static final Color HOVER = new Color(1f, 1f, 1f, 0.07f);
    private static final Color ACCENT = new Color(0.40f, 0.70f, 1f, 1f);
    private static final Color TEXT = new Color(0.93f, 0.93f, 0.95f, 1f);
    private static final Color DIM = new Color(0.58f, 0.60f, 0.66f, 1f);
    private static final Color WARN = new Color(1f, 0.72f, 0.28f, 1f);
    private static final Color BAD = new Color(1f, 0.45f, 0.40f, 1f);
    private static final Color GOOD = new Color(0.55f, 0.90f, 0.55f, 1f);
    private static final Color FIELD = new Color(1f, 1f, 1f, 0.09f);

    private final Config cfg;
    private final boolean showHint;
    private OptionsMenu menu;
    private InputHandler input;

    private boolean open;
    private int tab, sel, top, bindCol;
    private Modal modal = Modal.NONE;
    private Item.Bind capItem;
    private int capSlot;
    private long capUntil;
    private OptionsMenu.Conflict conflict;
    private int quitSel;                       // 0 cancel, 1 quit

    // held direction (d-pad / stick) with key repeat
    private int dpadX, dpadY, stickX, stickY, heldX, heldY;
    private long repeatAt;

    // mouse (backbuffer px, y down); a scripted position overrides the real mouse
    private boolean mouseOverride, scriptHeld, wasDown;
    private float omx, omy, lastMx = -1, lastMy = -1;
    private Item drag;
    private boolean ensureVisible = true;

    // drawing
    private BitmapFont font;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private final GlyphLayout gl = new GlyphLayout();
    private final Matrix4 proj = new Matrix4();
    private final List<float[]>[] shapeCmds = newLists();
    private final List<Object[]>[] textCmds = newLists();
    private float bw, bh, ui, rowH, lineH, panelW, pad, listTop, listBottom, valueX, valueW;
    private float[][] tabRects = new float[0][];
    private float[][] btnRects = new float[0][];
    private int visibleRows;
    private long hintStart;
    private boolean hintDone;

    @SuppressWarnings("unchecked")
    private static <T> List<T>[] newLists() {
        List<T>[] l = new List[2];
        l[0] = new ArrayList<>();
        l[1] = new ArrayList<>();
        return l;
    }

    public OptionsOverlay(Config cfg, boolean showHint) {
        this.cfg = cfg;
        this.showHint = showHint && !cfg.getBool("options.hintShown");
    }

    public void attach(OptionsMenu menu, InputHandler input) {
        this.menu = menu;
        this.input = input;
    }

    public boolean isOpen() { return open; }

    public boolean capturing() { return open && modal == Modal.CAPTURE; }

    // ---------------------------------------------------------------------------------------------- open / close

    public void toggle() {
        if (open) close(); else open();
    }

    public void open() {
        if (open || menu == null) return;
        open = true;
        modal = Modal.NONE;
        dpadX = dpadY = stickX = stickY = heldX = heldY = 0;
        input.releaseAllInput();
        RenderConfig.setPaused(true);
        ensureVisible = true;
        Gdx.graphics.requestRendering();
        System.err.println("[options] opened (game paused)");
    }

    public void close() {
        if (!open) return;
        open = false;
        modal = Modal.NONE;
        drag = null;
        menu.flush();
        input.releaseAllInput();
        RenderConfig.setPaused(false);
        System.err.println("[options] closed (game resumed)");
    }

    private OptionsMenu.Tab curTab() { return menu.tabs.get(tab); }

    private Item curItem() {
        List<Item> l = curTab().items;
        return sel >= 0 && sel < l.size() ? l.get(sel) : null;
    }

    // ---------------------------------------------------------------------------------------------- input

    /** Keyboard (libGDX key code); only called while open. */
    public void keyDown(int k) {
        if (modal == Modal.CAPTURE) { captureKey(k); return; }
        switch (k) {
            case Input.Keys.UP: nav(0, -1); break;
            case Input.Keys.DOWN: nav(0, 1); break;
            case Input.Keys.LEFT: nav(-1, 0); break;
            case Input.Keys.RIGHT: nav(1, 0); break;
            case Input.Keys.ENTER: case Input.Keys.NUMPAD_ENTER: case Input.Keys.SPACE: confirm(); break;
            case Input.Keys.ESCAPE: back(); break;
            case Input.Keys.TAB:
                tabStep(Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT) ? -1 : 1);
                break;
            case Input.Keys.PAGE_UP: tabStep(-1); break;
            case Input.Keys.PAGE_DOWN: tabStep(1); break;
            case Input.Keys.BACKSPACE: case Input.Keys.FORWARD_DEL: clearSlot(); break;
            case Input.Keys.HOME: moveSel(-1000); break;
            case Input.Keys.END: moveSel(1000); break;
            default:
        }
    }

    public void padButton(String name, boolean down) {
        switch (name) {
            case "DPAD_UP": dpadY = down ? -1 : (dpadY == -1 ? 0 : dpadY); heldChanged(); break;
            case "DPAD_DOWN": dpadY = down ? 1 : (dpadY == 1 ? 0 : dpadY); heldChanged(); break;
            case "DPAD_LEFT": dpadX = down ? -1 : (dpadX == -1 ? 0 : dpadX); heldChanged(); break;
            case "DPAD_RIGHT": dpadX = down ? 1 : (dpadX == 1 ? 0 : dpadX); heldChanged(); break;
            default:
                if (!down) return;
                if (modal == Modal.CAPTURE) { captureButton(name); return; }
                switch (name) {
                    case "A": confirm(); break;
                    case "B": back(); break;
                    case "X": clearSlot(); break;
                    case "L1": tabStep(-1); break;
                    case "R1": tabStep(1); break;
                    default:
                }
        }
    }

    public void padStick(int x, int y) {
        stickX = x;
        stickY = y;
        heldChanged();
    }

    private void heldChanged() {
        int nx = dpadX != 0 ? dpadX : stickX, ny = dpadY != 0 ? dpadY : stickY;
        if (nx == heldX && ny == heldY) return;
        heldX = nx;
        heldY = ny;
        if ((nx != 0 || ny != 0) && modal != Modal.CAPTURE) {
            if (ny != 0) nav(0, ny); else nav(nx, 0);
            repeatAt = System.currentTimeMillis() + 380;
        }
    }

    public void scrolled(float amountY) {
        if (modal != Modal.NONE) return;
        int max = Math.max(0, curTab().items.size() - Math.max(1, visibleRows));
        top = Math.max(0, Math.min(max, top + (amountY > 0 ? 3 : -3)));
        ensureVisible = false;
    }

    private void tabStep(int d) {
        if (modal != Modal.NONE) return;
        selectTab((tab + d + menu.tabs.size()) % menu.tabs.size());
    }

    private void selectTab(int t) {
        tab = t;
        sel = -1;
        top = 0;
        bindCol = 0;
        moveSel(1);
    }

    /** Moves the selection by {@code d} selectable rows (|d| >= 1000: first / last). */
    private void moveSel(int d) {
        List<Item> l = curTab().items;
        int n = l.size();
        int dir = d < 0 ? -1 : 1;
        int i = sel;
        if (Math.abs(d) >= 1000) i = d < 0 ? -1 : n;
        for (int step = 0; step < Math.abs(d) && step < 1000; step++) {
            int j = i + dir;
            while (j >= 0 && j < n && !l.get(j).selectable()) j += dir;
            if (j < 0 || j >= n) break;
            i = j;
        }
        if (Math.abs(d) >= 1000 && (i < 0 || i >= n)) { // first / last selectable
            i = d < 0 ? 0 : n - 1;
            while (i >= 0 && i < n && !l.get(i).selectable()) i -= dir;
        }
        if (i >= 0 && i < n) sel = i;
        ensureVisible = true;
    }

    private void nav(int dx, int dy) {
        if (modal == Modal.QUIT) {
            if (dx != 0) quitSel = dx > 0 ? 1 : 0;
            return;
        }
        if (modal != Modal.NONE) return;
        if (dy != 0) { moveSel(dy); return; }
        Item it = curItem();
        if (it instanceof Item.Bind) bindCol = Math.max(0, Math.min(1, bindCol + dx));
        else if (it != null) it.step(dx);
    }

    private void confirm() {
        switch (modal) {
            case CONFLICT: menu.resolveSwap(conflict); conflict = null; modal = Modal.NONE; return;
            case QUIT:
                if (quitSel == 1) { menu.flush(); quitNow(); }
                modal = Modal.NONE;
                return;
            case NONE: break;
            default: return;
        }
        activate(curItem());
    }

    private void activate(Item it) {
        if (it == null) return;
        if (it instanceof Item.Bind) {
            beginCapture((Item.Bind) it, bindCol);
        } else if (it instanceof Item.Action && ((Item.Action) it).danger) {
            modal = Modal.QUIT;
            quitSel = 0;
        } else {
            it.activate();
        }
    }

    private Runnable quitHook = () -> { };

    public OptionsOverlay onQuit(Runnable r) { quitHook = r; return this; }

    private void quitNow() { quitHook.run(); }

    private void back() {
        if (modal == Modal.CONFLICT) { conflict = null; modal = Modal.NONE; menu.toast("Cancelled"); }
        else if (modal == Modal.QUIT) modal = Modal.NONE;
        else if (modal == Modal.NONE) close();
    }

    private void clearSlot() {
        if (modal != Modal.NONE) return;
        Item it = curItem();
        if (it instanceof Item.Bind) menu.clear((Item.Bind) it, bindCol);
    }

    // ---- binding capture

    private void beginCapture(Item.Bind b, int slot) {
        capItem = b;
        capSlot = slot;
        modal = Modal.CAPTURE;
        capUntil = System.currentTimeMillis() + (b.pad ? 6000 : 15000);
    }

    private void captureKey(int k) {
        if (k == Input.Keys.ESCAPE) { endCapture("Cancelled"); return; }
        if (k == Input.Keys.BACKSPACE || k == Input.Keys.FORWARD_DEL) {
            Item.Bind b = capItem;
            int s = capSlot;
            modal = Modal.NONE;
            menu.clear(b, s);
            return;
        }
        if (capItem.pad) return; // a pad button is expected
        String name = OptionsMenu.keyName(k);
        if (name == null) { menu.toast("That key cannot be used (F3 F5 F6 F7 F8 F10 F11 are reserved)"); return; }
        finishCapture(name);
    }

    private void captureButton(String name) {
        if (!capItem.pad) return;
        finishCapture(name);
    }

    private void finishCapture(String name) {
        OptionsMenu.Conflict c = menu.assign(capItem, capSlot, name);
        if (c == null) { modal = Modal.NONE; return; }
        conflict = c;
        modal = Modal.CONFLICT;
    }

    private void endCapture(String msg) {
        modal = Modal.NONE;
        menu.toast(msg);
    }

    // ---------------------------------------------------------------------------------------------- scripting (tests)

    /** Test commands: open | close | key NAME | pad NAME | stick X Y | tab N | sel N | col N | mouse X Y | click | release | wheel N */
    public void script(String line) {
        String[] t = line.trim().split("\\s+");
        switch (t[0].toLowerCase()) {
            case "open": open(); break;
            case "close": close(); break;
            case "toggle": toggle(); break;
            case "key": { // through the real input handler: tests the routing, incl. reserved keys and the game keys
                int code = InputHandler.keyByName(t[1]);
                if (code < 0) { System.err.println("[options] unknown key " + t[1]); break; }
                input.keyDown(code);
                input.keyUp(code);
                break;
            }
            case "pad": input.padButton(t[1].toUpperCase(), t.length < 3 || !t[2].equalsIgnoreCase("up")); break;
            case "stick": if (open) padStick(Integer.parseInt(t[1]), Integer.parseInt(t[2])); break;
            case "tab": if (open) selectTab(Integer.parseInt(t[1])); break;
            case "sel": if (open) { sel = Integer.parseInt(t[1]); ensureVisible = true; } break;
            case "col": bindCol = Integer.parseInt(t[1]); break;
            case "mouse": mouseOverride = true; omx = Float.parseFloat(t[1]); omy = Float.parseFloat(t[2]); break;
            case "click": scriptHeld = true; break;
            case "release": scriptHeld = false; break;
            case "wheel": scrolled(Float.parseFloat(t[1])); break;
            case "get": System.err.println("[options] " + t[1] + " = " + (t[1].equals("gfx.quality") ? gothic3.desktop.GraphicsPresets.current(cfg) : cfg.get(t[1]))); break;
            case "bound": System.err.println("[options] " + t[1] + " -> midp " + input.midpFor(InputHandler.keyByName(t[1]))); break;
            case "set": menu.change(t[1], t.length > 2 ? t[2] : ""); break;
            case "state": System.err.println("[options] open=" + open + " tab=" + tab + " sel=" + sel + " modal=" + modal + " paused=" + RenderConfig.paused()
                    + " ticks=" + RenderConfig.ticks() + " t=" + System.currentTimeMillis() % 1000000); break;
            default: System.err.println("[options] unknown script command: " + line);
        }
    }

    // ---------------------------------------------------------------------------------------------- frame

    private void init() {
        font = new BitmapFont(true);
        font.getRegion().getTexture().setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
    }

    public void dispose() {
        if (menu != null) menu.flush();
        if (font != null) font.dispose();
        if (batch != null) batch.dispose();
        if (shapes != null) shapes.dispose();
    }

    /** Called once per presented frame (render thread), after the game picture is drawn. */
    public void render(int backW, int backH) {
        if (menu == null) return;
        bw = backW;
        bh = backH;
        if (font == null) init();
        if (!open) {
            drawStartHint();
            return;
        }
        menu.tick();
        long now = System.currentTimeMillis();
        if (modal == Modal.CAPTURE && now > capUntil) endCapture("Cancelled (no input)");
        if ((heldX != 0 || heldY != 0) && modal != Modal.CAPTURE && now >= repeatAt) {
            if (heldY != 0) nav(0, heldY); else nav(heldX, 0);
            repeatAt = now + 90;
        }
        ui = Math.max(0.8f, Math.min(3f, bh / 720f));
        font.getData().setScale(1.12f * ui);
        lineH = font.getLineHeight();
        rowH = Math.round(lineH * 1.6f);
        pad = Math.round(16 * ui);
        panelW = Math.min(bw * 0.97f, Math.round(620 * ui));
        valueX = Math.round(panelW * 0.47f);
        valueW = panelW - valueX - pad;
        layoutList();
        handleMouse();
        layoutList();
        compose();
        flush();
    }

    private void layoutList() {
        float tabsY = pad + lineH * 1.5f + 4 * ui;
        listTop = Math.round(tabsY + lineH * 1.5f + 8 * ui);
        listBottom = Math.round(bh - pad - lineH * 2.7f);
        visibleRows = Math.max(1, (int) ((listBottom - listTop) / rowH));
        int n = curTab().items.size();
        if (ensureVisible && sel >= 0) {
            if (sel < top) top = sel;
            if (sel >= top + visibleRows) top = sel - visibleRows + 1;
            // keep a header row above the first selectable item visible
            if (top == 1 && !curTab().items.get(0).selectable()) top = 0;
        }
        top = Math.max(0, Math.min(Math.max(0, n - visibleRows), top));
    }

    private float mouseX() { return mouseOverride ? omx : Gdx.input.getX() * bw / Math.max(1f, Gdx.graphics.getWidth()); }

    private float mouseY() { return mouseOverride ? omy : Gdx.input.getY() * bh / Math.max(1f, Gdx.graphics.getHeight()); }

    private static boolean in(float x, float y, float rx, float ry, float rw, float rh) { return x >= rx && x < rx + rw && y >= ry && y < ry + rh; }

    private void handleMouse() {
        float mx = mouseX(), my = mouseY();
        boolean moved = Math.abs(mx - lastMx) > 0.5f || Math.abs(my - lastMy) > 0.5f;
        lastMx = mx;
        lastMy = my;
        boolean down = mouseOverride ? scriptHeld : Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        boolean pressed = down && !wasDown;
        if (!down) drag = null;
        wasDown = down;
        if (modal != Modal.NONE) {
            if (pressed) clickModal(mx, my);
            return;
        }
        List<Item> items = curTab().items;
        int row = (my >= listTop && my < listTop + visibleRows * rowH) ? top + (int) ((my - listTop) / rowH) : -1;
        boolean overList = row >= 0 && row < items.size() && mx < panelW;
        if (moved && overList && items.get(row).selectable()) {
            sel = row;
            Item it = items.get(row);
            if (it instanceof Item.Bind && mx >= valueX) bindCol = mx >= valueX + valueW / 2 ? 1 : 0;
        }
        if (drag != null && down) {
            drag.setFrac((mx - valueX) / Math.max(1f, valueW - 62 * ui));
            return;
        }
        if (!pressed) return;
        for (int i = 0; i < tabRects.length; i++) {
            float[] r = tabRects[i];
            if (in(mx, my, r[0], r[1], r[2], r[3])) { selectTab(i); return; }
        }
        if (!overList || !items.get(row).selectable()) return;
        sel = row;
        Item it = items.get(row);
        if (it instanceof Item.Bind) {
            bindCol = mx >= valueX + valueW / 2 ? 1 : 0;
            if (mx >= valueX) beginCapture((Item.Bind) it, bindCol);
        } else if (it.isSlider()) {
            if (mx >= valueX) {
                drag = it;
                it.setFrac((mx - valueX) / Math.max(1f, valueW - 62 * ui));
            }
        } else if (it instanceof Item.Setting) {
            if (mx >= valueX + valueW / 2) it.step(1); else if (mx >= valueX) it.step(-1);
        } else {
            activate(it);
        }
    }

    private void clickModal(float mx, float my) {
        for (int i = 0; i < btnRects.length; i++) {
            float[] r = btnRects[i];
            if (!in(mx, my, r[0], r[1], r[2], r[3])) continue;
            if (modal == Modal.QUIT) { quitSel = i; confirm(); }
            else if (modal == Modal.CONFLICT) { if (i == 0) confirm(); else back(); }
            else if (modal == Modal.CAPTURE) endCapture("Cancelled");
            return;
        }
    }

    // ---------------------------------------------------------------------------------------------- drawing (command lists, two layers)

    private void rect(int layer, float x, float y, float w, float h, Color c) {
        shapeCmds[layer].add(new float[] {x, y, w, h, c.r, c.g, c.b, c.a});
    }

    private void frame(int layer, float x, float y, float w, float h, Color c, float t) {
        rect(layer, x, y, w, t, c);
        rect(layer, x, y + h - t, w, t, c);
        rect(layer, x, y, t, h, c);
        rect(layer, x + w - t, y, t, h, c);
    }

    /** align: 0 left, 1 centre, 2 right (x is the anchor). */
    private void text(int layer, String s, float x, float y, float scale, Color c, int align) {
        textCmds[layer].add(new Object[] {s, x, y, scale, c, align});
    }

    private void compose() {
        for (int l = 0; l < 2; l++) { shapeCmds[l].clear(); textCmds[l].clear(); }
        rect(0, 0, 0, panelW, bh, BG);
        rect(0, panelW - 2 * ui, 0, 2 * ui, bh, ACCENT);
        text(0, "OPTIONS", pad, pad, 1.25f, ACCENT, 0);
        text(0, "Game paused", panelW - pad - 4 * ui, pad + 2 * ui, 0.85f, DIM, 2);

        // tabs
        float ty = pad + lineH * 1.5f + 4 * ui;
        tabRects = new float[menu.tabs.size()][];
        float tx = pad;
        float tabGap = 18 * ui;
        for (int i = 0; i < menu.tabs.size(); i++) {
            String nm = menu.tabs.get(i).name;
            gl.setText(font, nm);
            float w = gl.width;
            tabRects[i] = new float[] {tx - 6 * ui, ty - 2 * ui, w + 12 * ui, lineH * 1.5f};
            if (i == tab) {
                rect(0, tx - 6 * ui, ty + lineH * 1.35f, w + 12 * ui, 3 * ui, ACCENT);
                text(0, nm, tx, ty, 1f, TEXT, 0);
            } else {
                text(0, nm, tx, ty, 1f, DIM, 0);
            }
            tx += w + tabGap;
        }
        rect(0, pad, listTop - 6 * ui, panelW - 2 * pad, 1, FIELD);

        // rows
        List<Item> items = curTab().items;
        boolean binds = curTab().hasBinds();
        float cellW = valueW / 2 - 4 * ui;
        for (int r = 0; r < visibleRows && top + r < items.size(); r++) {
            int idx = top + r;
            Item it = items.get(idx);
            float y = listTop + r * rowH;
            float ty0 = y + (rowH - lineH) / 2;
            if (it instanceof Item.Header) {
                if (binds) {
                    text(0, "Primary", valueX + cellW / 2, ty0, 0.85f, DIM, 1);
                    text(0, "Secondary", valueX + valueW / 2 + 4 * ui + cellW / 2, ty0, 0.85f, DIM, 1);
                } else {
                    text(0, it.label, pad, ty0, 0.85f, DIM, 0);
                }
                continue;
            }
            boolean s = idx == sel;
            if (s) rect(0, 4 * ui, y, panelW - 8 * ui - 2 * ui, rowH - 2 * ui, SEL);
            if (it instanceof Item.Action) {
                Item.Action a = (Item.Action) it;
                text(0, it.label, pad, ty0, 1f, a.danger ? BAD : (s ? TEXT : ACCENT), 0);
                continue;
            }
            text(0, it.label, pad, ty0, 1f, TEXT, 0);
            if (it.restart) text(0, "(restart)", valueX - 10 * ui, ty0 + 1, 0.75f, WARN, 2);
            if (it instanceof Item.Bind) {
                Item.Bind b = (Item.Bind) it;
                for (int c = 0; c < 2; c++) {
                    float cx = valueX + c * (valueW / 2 + 4 * ui);
                    rect(0, cx, y + 2 * ui, cellW, rowH - 6 * ui, FIELD);
                    if (s && bindCol == c) frame(0, cx, y + 2 * ui, cellW, rowH - 6 * ui, ACCENT, Math.max(1, ui));
                    String n = menu.bindName(b, c);
                    if (c == 1 && menu.extraBindings(b) > 0) n = (n.isEmpty() ? "" : n + " ") + "+" + menu.extraBindings(b);
                    text(0, n.isEmpty() ? "-" : n, cx + cellW / 2, ty0, 0.9f, n.isEmpty() ? DIM : TEXT, 1);
                }
            } else if (it.isSlider()) {
                float valW = 60 * ui;
                float trackW = valueW - valW - 8 * ui;
                float cy = y + rowH / 2f - 1;
                rect(0, valueX, cy - 2 * ui, trackW, 4 * ui, FIELD);
                rect(0, valueX, cy - 2 * ui, trackW * it.frac(), 4 * ui, s ? ACCENT : DIM);
                rect(0, valueX + trackW * it.frac() - 4 * ui, cy - 7 * ui, 8 * ui, 14 * ui, s ? TEXT : DIM);
                text(0, it.text(), panelW - pad, ty0, 1f, TEXT, 2);
            } else {
                String v = it.text();
                Color vc = v.equals("on") ? GOOD : v.equals("off") ? DIM : TEXT;
                float mid = valueX + valueW / 2;
                text(0, v, mid, ty0, 1f, vc, 1);
                if (s) {
                    text(0, "<", valueX + 4 * ui, ty0, 1f, ACCENT, 0);
                    text(0, ">", valueX + valueW - 4 * ui, ty0, 1f, ACCENT, 2);
                }
            }
        }
        if (items.size() > visibleRows) { // scrollbar
            float th = (listBottom - listTop);
            float bh2 = Math.max(16 * ui, th * visibleRows / items.size());
            float by = listTop + (th - bh2) * top / Math.max(1, items.size() - visibleRows);
            rect(0, panelW - 7 * ui, listTop, 3 * ui, th, FIELD);
            rect(0, panelW - 7 * ui, by, 3 * ui, bh2, DIM);
        }

        // footer: toast > restart note > item hint > key legend
        float fy = bh - pad - lineH * 3.5f; // three footer lines: hint/toast, restart note or pad legend, always-on hotkey legend
        rect(0, pad, fy - 4 * ui, panelW - 2 * pad, 1, FIELD);
        Item cur = curItem();
        List<String> notes = menu.restartNotes();
        String l1;
        Color c1 = DIM;
        if (System.currentTimeMillis() < menu.toastUntil) { l1 = menu.toast; c1 = TEXT; }
        else if (cur instanceof Item.Bind) l1 = "Enter: rebind   Del: clear   Left/Right: primary / secondary";
        else if (cur != null && !cur.hint.isEmpty()) l1 = cur.hint;
        else l1 = "";
        text(0, l1, pad, fy, 0.85f, c1, 0);
        if (!notes.isEmpty()) text(0, "Restart required: " + String.join(", ", notes), pad, fy + lineH * 1.1f, 0.85f, WARN, 0);
        else text(0, "Pad:  D-pad = move,  A = select, B = back, X = clear,  L1/R1 = tabs,  Start+Back = close", pad, fy + lineH * 1.1f, 0.78f, DIM, 0);

        text(0, "F5: 2D/3D   F8: fast forward   F3: status   F10 / Esc: close   Tab: next tab", pad, fy + lineH * 2.2f, 0.85f, TEXT, 0);

        btnRects = new float[0][];
        if (modal != Modal.NONE) composeModal();
    }

    private void composeModal() {
        rect(1, 0, 0, panelW, bh, new Color(0, 0, 0, 0.55f));
        float w = panelW - 3 * pad, h = lineH * 6.4f;
        float x = (panelW - w) / 2, y = Math.round(bh * 0.36f);
        rect(1, x, y, w, h, new Color(0.08f, 0.09f, 0.14f, 1f));
        frame(1, x, y, w, h, ACCENT, Math.max(1, ui));
        float cx = x + w / 2;
        switch (modal) {
            case CAPTURE: {
                String what = capItem.pad ? "a gamepad button" : "a key";
                text(1, "Press " + what + " for", cx, y + lineH * 0.7f, 1f, TEXT, 1);
                text(1, capItem.label + "  (" + (capSlot == 0 ? "primary" : "secondary") + ")", cx, y + lineH * 1.8f, 1.1f, ACCENT, 1);
                text(1, "Backspace clears   Esc cancels", cx, y + lineH * 3.2f, 0.85f, DIM, 1);
                long left = Math.max(0, (capUntil - System.currentTimeMillis()) / 1000);
                text(1, capItem.pad ? "times out in " + left + " s" : "", cx, y + lineH * 4.2f, 0.8f, DIM, 1);
                btnRects = new float[0][];
                break;
            }
            case CONFLICT: {
                OptionsMenu.Conflict c = conflict;
                text(1, c.name + " is already used by", cx, y + lineH * 0.7f, 1f, WARN, 1);
                text(1, c.other.label + " (slot " + (c.otherSlot + 1) + ")", cx, y + lineH * 1.7f, 1.1f, TEXT, 1);
                text(1, c.old != null ? "Swap the two bindings?" : "Move it here?", cx, y + lineH * 2.8f, 0.9f, DIM, 1);
                buttons(y + lineH * 4.1f, cx, "Swap (Enter)", "Cancel (Esc)", 0);
                break;
            }
            default: {
                text(1, "Quit the game?", cx, y + lineH * 0.9f, 1.15f, BAD, 1);
                text(1, "Progress since the last save is lost.", cx, y + lineH * 2.2f, 0.85f, DIM, 1);
                buttons(y + lineH * 4.0f, cx, "Cancel", "Quit", quitSel);
            }
        }
    }

    private void buttons(float y, float cx, String a, String b, int selected) {
        float bwid = 150 * ui, bhei = lineH * 1.5f, gap = 14 * ui;
        float x0 = cx - bwid - gap / 2, x1 = cx + gap / 2;
        btnRects = new float[][] {{x0, y, bwid, bhei}, {x1, y, bwid, bhei}};
        rect(1, x0, y, bwid, bhei, selected == 0 ? SEL : FIELD);
        rect(1, x1, y, bwid, bhei, selected == 1 ? SEL : FIELD);
        text(1, a, x0 + bwid / 2, y + (bhei - lineH) / 2, 1f, TEXT, 1);
        text(1, b, x1 + bwid / 2, y + (bhei - lineH) / 2, 1f, TEXT, 1);
    }

    private void flush() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glViewport(0, 0, (int) bw, (int) bh);
        proj.setToOrtho2D(0, bh, bw, -bh);
        shapes.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);
        for (int l = 0; l < 2; l++) {
            Gdx.gl.glEnable(GL20.GL_BLEND); // SpriteBatch.end() switches blending off
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (float[] r : shapeCmds[l]) {
                shapes.setColor(r[4], r[5], r[6], r[7]);
                shapes.rect(r[0], r[1], r[2], r[3]);
            }
            shapes.end();
            drawTexts(l);
        }
        font.getData().setScale(1f);
    }

    private void drawTexts(int l) {
        float base = 1.12f * ui;
        batch.begin();
        for (Object[] t : textCmds[l]) {
            String s = (String) t[0];
            if (s.isEmpty()) continue;
            font.getData().setScale(base * (Float) t[3]);
            gl.setText(font, s);
            float x = (Float) t[1];
            int align = (Integer) t[5];
            if (align == 1) x -= gl.width / 2;
            else if (align == 2) x -= gl.width;
            Color c = (Color) t[4];
            font.setColor(0, 0, 0, c.a * 0.6f);
            font.draw(batch, s, x + Math.max(1, ui * 0.8f), (Float) t[2] + Math.max(1, ui * 0.8f));
            font.setColor(c);
            font.draw(batch, s, x, (Float) t[2]);
        }
        batch.end();
    }

    /** One-time "F10: options" hint on the first start. */
    private void drawStartHint() {
        if (!showHint || hintDone) return;
        long now = System.currentTimeMillis();
        if (hintStart == 0) hintStart = now;
        if (now - hintStart > 9000) {
            hintDone = true;
            cfg.set("options.hintShown", "true");
            cfg.save();
            return;
        }
        ui = Math.max(0.8f, Math.min(3f, bh / 720f));
        font.getData().setScale(1.05f * ui);
        String s = "F10: options    (gamepad: Start + Back)";
        gl.setText(font, s);
        float w = gl.width + 28 * ui, h = font.getLineHeight() + 16 * ui;
        float x = (bw - w) / 2, y = bh - h - 22 * ui;
        float a = Math.min(1f, Math.min((now - hintStart) / 400f, (hintStart + 9000 - now) / 600f));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        proj.setToOrtho2D(0, bh, bw, -bh);
        shapes.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.04f, 0.05f, 0.08f, 0.82f * a);
        shapes.rect(x, y, w, h);
        shapes.setColor(0.40f, 0.70f, 1f, a);
        shapes.rect(x, y + h - 2 * ui, w, 2 * ui);
        shapes.end();
        batch.begin();
        font.setColor(0.93f, 0.93f, 0.95f, a);
        font.draw(batch, s, x + 14 * ui, y + 8 * ui);
        batch.end();
        font.getData().setScale(1f);
    }
}
