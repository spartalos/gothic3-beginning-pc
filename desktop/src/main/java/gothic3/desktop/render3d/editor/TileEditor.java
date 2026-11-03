package gothic3.desktop.render3d.editor;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import gothic3.desktop.render3d.Renderer3D;
import gothic3.desktop.render3d.TileTable;
import gothic3.desktop.render3d.TileTable.Kind;
import gothic3.desktop.render3d.TileTable.Level;
import gothic3.desktop.render3d.TileTable.Resolved;
import gothic3.desktop.render3d.TileTable.Shape;
import gothic3.desktop.render3d.TileTable.Spec;
import gothic3.platform.scene3d.MapSnapshot;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.microedition.lcdui.Image;

/**
 * In-game tile tagging editor (F7, docs/render3d.md "Tile editor"). Render / input thread only. Hover picking against
 * {@link PickGeometry}, selection, shape edits written into the live {@link TileTable} (mesh rebuilt immediately),
 * undo / redo, a palette of the tileset sheet, and saving back to the table file ({@link TableFile}).
 * The game keeps running underneath; the editor never touches game state.
 */
public final class TileEditor extends InputAdapter {
    private enum Scope {
        TILESET("tileset"), MAP("this map"), CELL("this cell");
        final String label;
        Scope(String l) { label = l; }
    }

    /** One undoable edit: the entry at (scope, key) goes from {@code before} to {@code after} (null = no entry). */
    private static final class Change {
        Scope scope;
        int layer, tileset, mapId, tile, depth, x, y;
        Shape before, after;

        Spec spec() {
            Spec s = new Spec();
            s.layer = layer;
            switch (scope) {
                case TILESET: s.tileset = tileset; s.lo = s.hi = tile; s.depth = depth; break;
                case MAP: s.mapId = mapId; s.lo = s.hi = tile; s.depth = depth; break;
                default: s.type = Spec.Type.CELL; s.mapId = mapId; s.cx = x; s.cy = y;
            }
            return s;
        }
    }

    private static final class Dirty {
        Spec spec;
        Shape original, current;
    }

    private static final class Cmd {
        int z; // 0 background fill, 1 image, 2 overlay fill / outline, 3 text
        boolean outline;
        float x, y, w, h, scale;
        Color c;
        String s;
        Texture t;
    }

    private static final class Button {
        float x, y, w, h;
        Runnable action;
    }

    private static final int NO_CELL = 60000;
    private static final Color COLOR_FALLBACK = new Color(0.8f, 0.8f, 0.8f, 1);
    /** Badge / button colour per kind; looked up by kind (never by ordinal) so a new kind can not crash the editor. */
    private static final java.util.EnumMap<Kind, Color> KIND_COLOR = new java.util.EnumMap<>(Kind.class);
    static {
        KIND_COLOR.put(Kind.FLOOR, new Color(0.55f, 0.80f, 0.45f, 1));
        KIND_COLOR.put(Kind.FLAT, new Color(0.30f, 0.85f, 0.85f, 1));
        KIND_COLOR.put(Kind.UPRIGHT, new Color(0.35f, 0.55f, 1.00f, 1));
        KIND_COLOR.put(Kind.SLOPE, new Color(1.00f, 0.60f, 0.15f, 1));
        KIND_COLOR.put(Kind.CROSS, new Color(0.90f, 0.40f, 0.90f, 1));
        KIND_COLOR.put(Kind.BLOCK, new Color(0.95f, 0.30f, 0.30f, 1));
        KIND_COLOR.put(Kind.HIDDEN, new Color(0.60f, 0.60f, 0.60f, 1));
        KIND_COLOR.put(Kind.STEPS, new Color(0.85f, 0.80f, 0.30f, 1));
        KIND_COLOR.put(Kind.DECK, new Color(0.65f, 0.45f, 0.25f, 1));
        KIND_COLOR.put(Kind.STAIRS, new Color(0.95f, 0.75f, 0.55f, 1));
        KIND_COLOR.put(Kind.FLIGHT, new Color(0.45f, 0.90f, 0.65f, 1));
    }
    private static Color kindColor(Kind k) { Color c = KIND_COLOR.get(k); return c != null ? c : COLOR_FALLBACK; }
    /** Hotkey label of the i-th kind: 1..9, 0 (10th), F (11th); later kinds have none (panel button only). */
    private static String kindKey(int i) { return i < 9 ? String.valueOf(i + 1) : i == 9 ? "0" : i == 10 ? "F" : "-"; }
    private static int kindForKey(int k) {
        if (k >= Input.Keys.NUM_1 && k <= Input.Keys.NUM_9) return k - Input.Keys.NUM_1;
        if (k >= Input.Keys.NUMPAD_1 && k <= Input.Keys.NUMPAD_9) return k - Input.Keys.NUMPAD_1;
        if (k == Input.Keys.NUM_0 || k == Input.Keys.NUMPAD_0) return 9;
        if (k == Input.Keys.F) return 10;
        return -1;
    }
    private static final Kind[] KINDS = Kind.values();

    // ---- state ----
    private boolean open;
    private boolean showPalette;
    private int palLayer = 1;
    private int palScroll;
    private Scope scope = Scope.TILESET;
    private boolean selHas, selCell;
    private int selLayer, selTile, selX, selY, selDepth = -1;
    private PickGeometry geo;
    private TileTable table;
    private PickGeometry.Quad hover;
    private int hoverPalTile = -1;
    private final List<Change> undo = new ArrayList<>(), redo = new ArrayList<>();
    private final LinkedHashMap<String, Dirty> dirty = new LinkedHashMap<>();
    private boolean needRebuild, closeWarned;
    private String msg = "";
    private long msgUntil;
    private boolean msgBad;
    private int lastMapId = -2;
    private Runnable onClose = () -> { };

    // mouse (backbuffer pixels, y up); scripted override until the real mouse moves
    private boolean mouseOverride;
    private float omx, omy;
    private int lastRealX = -1, lastRealY = -1;
    private boolean scriptCtrl, scriptShift, scriptAlt;

    // ---- drawing ----
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont font;
    private final Map<Image, Texture> palTex = new IdentityHashMap<>();
    private final List<Cmd> cmds = new ArrayList<>();
    private List<Button> buttons = new ArrayList<>();
    private final List<Button> nextButtons = new ArrayList<>();
    private float panelX, panelY, panelW, panelH, palX, palY, palW, palH;
    private float ui = 1f;
    // palette geometry of the last frame
    private float palSheetX, palSheetY, palScale;
    private Image palImage;
    private int palCols, palRows;

    public boolean isOpen() { return open; }

    public void open() {
        open = true;
        closeWarned = false;
        msg("Tile editor on. Hover + click a tile, 1-9/0/F set the shape, Ctrl+S saves, F7 closes.");
    }

    /** Runs when the editor closes (so the caller can restore things). */
    public TileEditor onClose(Runnable r) { onClose = r; return this; }

    public void toggle() {
        if (!open) open();
        else requestClose();
    }

    /** First call with unsaved changes only warns; the second closes (edits stay applied in memory). */
    public void requestClose() {
        if (!dirty.isEmpty() && !closeWarned) {
            closeWarned = true;
            msgBad = true;
            msg("UNSAVED CHANGES (" + dirty.size() + "): Ctrl+S saves, F7 / Esc again closes without saving.");
            return;
        }
        open = false;
        hover = null;
        onClose.run();
    }

    /** Closes without the unsaved warning (view switched to 2D). */
    public void forceClose() {
        if (open) {
            open = false;
            hover = null;
            onClose.run();
        }
    }

    private void msg(String s) {
        msg = s;
        msgUntil = System.currentTimeMillis() + 6000;
        if (!msgBad) System.err.println("[tile-editor] " + s);
        msgBad = false;
    }

    // ---------------------------------------------------------------------------------------------- input

    private static boolean ctrlDown() {
        return Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
    }

    private boolean ctrl() { return scriptCtrl || ctrlDown(); }

    private boolean alt() {
        return scriptAlt || Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
    }

    private boolean shift() {
        return scriptShift || Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
    }

    /** Returns true if the key is an editor key (the caller swallows all keys while the editor is open anyway). */
    @Override
    public boolean keyDown(int k) {
        if (!open) return false;
        boolean ctrl = ctrl();
        if (k != Input.Keys.ESCAPE && k != Input.Keys.F7) closeWarned = false;
        switch (k) {
            case Input.Keys.F7: requestClose(); return true;
            case Input.Keys.ESCAPE:
                if (selHas) selHas = false;
                else requestClose();
                return true;
            case Input.Keys.Z:
                if (ctrl) { if (shift()) redo(); else undo(); }
                return true;
            case Input.Keys.Y: if (ctrl) redo(); return true;
            case Input.Keys.S: if (ctrl) save(); return true;
            case Input.Keys.P: showPalette = !showPalette; return true;
            case Input.Keys.M: scope = Scope.values()[(scope.ordinal() + 1) % Scope.values().length]; return true;
            case Input.Keys.TAB: cycleLayer(); return true;
            case Input.Keys.FORWARD_DEL: case Input.Keys.DEL: removeEntry(); return true;
            case Input.Keys.PLUS: case Input.Keys.EQUALS: case Input.Keys.NUMPAD_ADD: adjust(+1); return true;
            case Input.Keys.MINUS: case Input.Keys.NUMPAD_SUBTRACT: adjust(-1); return true;
            case Input.Keys.LEFT: moveSel(-1, 0); return true;
            case Input.Keys.RIGHT: moveSel(1, 0); return true;
            case Input.Keys.UP: moveSel(0, -1); return true;
            case Input.Keys.DOWN: moveSel(0, 1); return true;
            default:
        }
        int ki = kindForKey(k);
        if (ki >= 0 && ki < KINDS.length) { setKind(KINDS[ki]); return true; }
        return false;
    }

    @Override
    public boolean scrolled(float ax, float ay) {
        if (!open) return false;
        if (palScrollable() && overPalette()) {
            palScroll += ay > 0 ? 1 : -1;
            return true;
        }
        if (ctrl()) { adjust(ay > 0 ? -1 : 1); return true; }
        return false; // camera zoom
    }

    @Override
    public boolean touchDown(int sx, int sy, int pointer, int button) {
        if (!open) return false;
        float[] m = realMouse();
        click(m[0], m[1], button == Input.Buttons.LEFT);
        return true;
    }

    private float[] realMouse() {
        int bw = Gdx.graphics.getBackBufferWidth(), bh = Gdx.graphics.getBackBufferHeight();
        float mx = Gdx.input.getX() * bw / (float) Math.max(1, Gdx.graphics.getWidth());
        float my = bh - Gdx.input.getY() * bh / (float) Math.max(1, Gdx.graphics.getHeight());
        return new float[] {mx, my};
    }

    private float[] mouse() {
        int rx = Gdx.input.getX(), ry = Gdx.input.getY();
        if (rx != lastRealX || ry != lastRealY) {
            if (lastRealX >= 0) mouseOverride = false;
            lastRealX = rx;
            lastRealY = ry;
        }
        return mouseOverride ? new float[] {omx, omy} : realMouse();
    }

    private static boolean in(float mx, float my, float x, float y, float w, float h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private boolean overPanel(float mx, float my) {
        return in(mx, my, panelX, panelY, panelW, panelH) || (showPalette && in(mx, my, palX, palY, palW, palH));
    }

    private boolean overPalette() {
        float[] m = mouse();
        return showPalette && in(m[0], m[1], palX, palY, palW, palH);
    }

    private void click(float mx, float my, boolean left) {
        if (!left) { selHas = false; return; }
        for (Button b : buttons) {
            if (in(mx, my, b.x, b.y, b.w, b.h)) { b.action.run(); return; }
        }
        int pt = paletteTileAt(mx, my);
        if (pt >= 0) {
            selHas = true;
            selCell = false;
            selLayer = palLayer;
            selTile = pt;
            selDepth = -1;
            selX = selY = NO_CELL;
            return;
        }
        if (overPanel(mx, my)) return;
        if (hover != null) selectCell(hover.layer, hover.cx, hover.cy);
        else selHas = false;
    }

    // ---------------------------------------------------------------------------------------------- selection

    private void selectCell(int layer, int x, int y) {
        if (geo == null) return;
        x = Math.max(0, Math.min(geo.map.w - 1, x));
        y = Math.max(0, Math.min(geo.map.h - 1, y));
        selHas = true;
        selCell = true;
        selLayer = layer;
        selX = x;
        selY = y;
        selTile = layer == 0 ? geo.tile0(x, y) : geo.tile1(x, y);
        selDepth = layer == 1 ? geo.depth(x, y) : -1;
        palLayer = layer;
    }

    private void cycleLayer() {
        if (selHas && selCell) selectCell(1 - selLayer, selX, selY);
        else {
            palLayer = 1 - palLayer;
            if (selHas) selHas = false;
        }
    }

    private void moveSel(int dx, int dy) {
        if (selHas && selCell) selectCell(selLayer, selX + dx, selY + dy);
    }

    private int selTileset() {
        return geo == null ? 0 : selLayer == 0 ? geo.map.tileset0 : geo.map.tileset1;
    }

    private Resolved resolveSel() {
        MapSnapshot m = geo.map;
        return table.resolve(m.mapId, selLayer, selTileset(), selTile, selDepth, selCell ? selX : NO_CELL, selCell ? selY : NO_CELL);
    }

    // ---------------------------------------------------------------------------------------------- editing

    private boolean canEdit() {
        if (geo == null || !selHas) { msg("Nothing selected: click a tile first."); return false; }
        if (selTile < 0) { msg("This cell has no tile on layer " + selLayer + "."); return false; }
        return true;
    }

    private void setKind(Kind k) {
        if (!canEdit()) return;
        Shape cur = resolveSel().shape;
        if (cur.kind == k) return;
        float p = 0;
        switch (k) {
            case SLOPE: p = 45f; break;
            case BLOCK: p = 0.5f; break;
            case DECK: p = TileTable.DECK_H; break;
            case STAIRS: case STEPS: p = 4f; break;
            case FLIGHT: p = 3f; break;
            default:
        }
        apply(new Shape(k, p));
    }

    /** +/-: the kind's parameter (Shift = fine). Alt + / Alt - : stairs / flight rise (second parameter). */
    private void adjust(int dir) {
        if (!canEdit()) return;
        Shape cur = resolveSel().shape;
        boolean fine = shift();
        boolean alt = alt();
        switch (cur.kind) {
            case SLOPE:
                apply(new Shape(Kind.SLOPE, Math.max(0, Math.min(85, cur.param + dir * (fine ? 1 : 5)))));
                break;
            case BLOCK:
                apply(new Shape(Kind.BLOCK, Math.max(0.25f, Math.min(8f, cur.param + dir * (fine ? 0.05f : 0.25f)))));
                break;
            case DECK:
                apply(new Shape(Kind.DECK, round2(Math.max(0.05f, Math.min(4f, cur.param + dir * (fine ? 0.05f : 0.25f))))));
                break;
            case STEPS:
                apply(new Shape(Kind.STEPS, Math.max(1, Math.min(16, Math.round(cur.param) + dir))));
                break;
            case STAIRS: case FLIGHT:
                if (alt) apply(new Shape(cur.kind, cur.param, round2(Math.max(0.05f, Math.min(4f, cur.param2 + dir * (fine ? 0.05f : 0.25f))))));
                else apply(new Shape(cur.kind, Math.max(1, Math.min(16, Math.round(cur.param) + dir)), cur.param2));
                break;
            default:
                msg("This shape has no parameter (slope, block, deck, steps, stairs, flight do).");
        }
    }

    private static float round2(float f) { return Math.round(f * 100f) / 100f; }

    private void removeEntry() {
        if (!canEdit()) return;
        Change c = makeChange(null);
        if (c.before == null) { msg("No " + scope.label + " entry to remove."); return; }
        commit(c);
    }

    private void apply(Shape s) {
        if (scope == Scope.CELL && !selCell) { msg("Cell scope needs a tile picked in the map (not the palette)."); return; }
        Change c = makeChange(s);
        if (s.equals(c.before)) return;
        commit(c);
        Resolved r = resolveSel();
        if (!r.shape.equals(s)) msg("Saved at " + scope.label + " scope, but shadowed by " + r.source + " (" + r.shape + ").");
    }

    private Change makeChange(Shape after) {
        Resolved r = resolveSel();
        MapSnapshot m = geo.map;
        Change c = new Change();
        c.scope = (scope == Scope.CELL && !selCell) ? Scope.MAP : scope;
        c.layer = selLayer;
        c.tileset = selTileset();
        c.mapId = m.mapId;
        c.tile = selTile;
        c.x = selX;
        c.y = selY;
        // a depth specific rule that provides the current shape is edited in place, else the entry is depth independent
        c.depth = (r.keyDepth >= 0 && ((c.scope == Scope.TILESET && r.level == Level.TABLE) || (c.scope == Scope.MAP && r.level == Level.MAP_TILE))) ? r.keyDepth : -1;
        c.after = after;
        c.before = current(c);
        return c;
    }

    private Shape current(Change c) {
        switch (c.scope) {
            case TILESET: return table.tileEntry(c.layer, c.tileset, c.tile, c.depth);
            case MAP: return table.mapTileEntry(c.mapId, c.layer, c.tile, c.depth);
            default: return table.cellEntry(c.mapId, c.layer, c.x, c.y);
        }
    }

    private void commit(Change c) {
        write(c, true);
        undo.add(c);
        redo.clear();
        if (undo.size() > 500) undo.remove(0);
    }

    private void write(Change c, boolean forward) {
        Shape v = forward ? c.after : c.before;
        Shape was = forward ? c.before : c.after;
        switch (c.scope) {
            case TILESET: table.setTile(c.layer, c.tileset, c.tile, c.depth, v); break;
            case MAP: table.setMapTile(c.mapId, c.layer, c.tile, c.depth, v); break;
            default: table.setCell(c.mapId, c.layer, c.x, c.y, v);
        }
        Spec sp = c.spec();
        String key = sp.format();
        Dirty d = dirty.get(key);
        if (d == null) {
            d = new Dirty();
            d.spec = sp;
            d.original = was;
            dirty.put(key, d);
        }
        d.current = v;
        if (java.util.Objects.equals(d.original, d.current)) dirty.remove(key);
        needRebuild = true;
        closeWarned = false;
        msg(key + " = " + (v == null ? "(removed)" : v.toString()) + (c.scope == Scope.TILESET ? "  (every use of the tile in tileset " + c.tileset + ")" : ""));
    }

    private void undo() {
        if (undo.isEmpty()) { msg("Nothing to undo."); return; }
        Change c = undo.remove(undo.size() - 1);
        write(c, false);
        redo.add(c);
    }

    private void redo() {
        if (redo.isEmpty()) { msg("Nothing to redo."); return; }
        Change c = redo.remove(redo.size() - 1);
        write(c, true);
        undo.add(c);
    }

    private void save() {
        if (table == null) return;
        if (dirty.isEmpty()) { msg("No changes to save."); return; }
        File f = table.saveTarget();
        if (f == null) { msgBad = true; msg("No save target."); return; }
        List<Spec> specs = new ArrayList<>();
        List<Shape> vals = new ArrayList<>();
        for (Dirty d : dirty.values()) { specs.add(d.spec); vals.add(d.current); }
        try {
            TableFile.save(f, specs, vals);
            int n = dirty.size();
            dirty.clear();
            closeWarned = false;
            msg("Saved " + n + " change(s) to " + f.getPath());
        } catch (Exception e) {
            msgBad = true;
            msg("SAVE FAILED: " + e);
            System.err.println("[tile-editor] save failed: " + e);
        }
    }

    // ---------------------------------------------------------------------------------------------- scripting (tests)

    /** Script command (see ScriptRunner "ed"): open | close | mouse X Y | click [right] | cell L X Y | select L X Y | tile L ID | key [ctrl+][shift+][alt+]NAME | palette | save | info */
    public void script(String line) {
        String[] t = line.trim().split("\\s+");
        switch (t[0]) {
            case "open": if (!open) open(); break;
            case "close": forceClose(); break;
            case "mouse": mouseOverride = true; omx = Float.parseFloat(t[1]); omy = Gdx.graphics.getBackBufferHeight() - Float.parseFloat(t[2]); break;
            case "click": { float[] m = mouse(); click(m[0], m[1], t.length < 2 || !t[1].equals("right")); break; }
            case "cell": {
                if (geo == null) break;
                PickGeometry.Quad q = geo.quadAt(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                if (q == null) { System.err.println("[tile-editor] no quad at " + line); break; }
                float[] c = project(q);
                mouseOverride = true;
                omx = c[0];
                omy = c[1];
                break;
            }
            case "select": selectCell(Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])); break;
            case "tile":
                selHas = true; selCell = false; selLayer = palLayer = Integer.parseInt(t[1]); selTile = Integer.parseInt(t[2]);
                selDepth = -1; selX = selY = NO_CELL;
                break;
            case "key": {
                String n = t[1];
                scriptCtrl = scriptShift = scriptAlt = false;
                for (;;) {
                    if (n.startsWith("alt+")) { scriptAlt = true; n = n.substring(4); continue; }
                    if (n.startsWith("ctrl+")) { scriptCtrl = true; n = n.substring(5); }
                    else if (n.startsWith("shift+")) { scriptShift = true; n = n.substring(6); }
                    else break;
                }
                int k = keyCode(n);
                if (k < 0) System.err.println("[tile-editor] unknown key " + n);
                else keyDown(k);
                scriptCtrl = scriptShift = scriptAlt = false;
                break;
            }
            case "palette": showPalette = !showPalette; break;
            case "layer": palLayer = Integer.parseInt(t[1]); break;
            case "scope": scope = Scope.valueOf(t[1].toUpperCase()); break;
            case "save": save(); break;
            case "info": info(); break;
            default: System.err.println("[tile-editor] unknown command: " + line);
        }
    }

    private static int keyCode(String n) {
        switch (n.toLowerCase()) {
            case "plus": return Input.Keys.PLUS;
            case "minus": return Input.Keys.MINUS;
            case "tab": return Input.Keys.TAB;
            case "del": return Input.Keys.FORWARD_DEL;
            case "esc": return Input.Keys.ESCAPE;
            case "f7": return Input.Keys.F7;
            default:
        }
        int k = Input.Keys.valueOf(n);
        if (k < 0 && !n.isEmpty()) k = Input.Keys.valueOf(Character.toUpperCase(n.charAt(0)) + n.substring(1));
        return k;
    }

    private void info() {
        StringBuilder sb = new StringBuilder("[tile-editor] info: open=" + open + " dirty=" + dirty.size() + " undo=" + undo.size() + " redo=" + redo.size());
        if (geo != null) sb.append(" map=").append(geo.map.mapId).append(" ").append(geo.map.w).append("x").append(geo.map.h)
                .append(" quads=").append(geo.quads.size());
        if (hover != null) sb.append(String.format(" hover=L%d cell(%d,%d) tile %d depth %d shape %s", hover.layer, hover.cx, hover.cy, hover.tile, hover.depth, hover.shape));
        if (selHas && geo != null && selTile >= 0) {
            Resolved r = resolveSel();
            sb.append(String.format(" sel=L%d tile %d cell(%d,%d) depth %d shape %s from %s [%s]", selLayer, selTile, selX, selY, selDepth, r.shape, r.source, r.level));
        }
        System.err.println(sb);
    }

    // ---------------------------------------------------------------------------------------------- frame

    private float[] project(PickGeometry.Quad q) {
        int[] vp = vpRect;
        Vector3 v = new Vector3();
        float sx = 0, sy = 0;
        for (int i = 0; i < 4; i++) {
            v.set(q.p[i * 3], q.p[i * 3 + 1], q.p[i * 3 + 2]).prj(camCombined);
            sx += vp[0] + (v.x + 1) / 2 * vp[2];
            sy += vp[1] + (v.y + 1) / 2 * vp[3];
        }
        return new float[] {sx / 4, sy / 4};
    }

    private com.badlogic.gdx.math.Matrix4 camCombined = new com.badlogic.gdx.math.Matrix4();
    private int[] vpRect = {0, 0, 1, 1};

    /** Called by the presenter every frame while the editor is open and the 3D scene was drawn. */
    public void render(Renderer3D r3d, int bw, int bh) {
        if (batch == null) {
            batch = new SpriteBatch();
            shapes = new ShapeRenderer();
            font = new BitmapFont();
        }
        ui = Math.max(1f, Math.min(2.5f, bh / 720f));
        TileTable t = r3d.table();
        if (t == null) return;
        if (t != table) { // F6 reload: the in-memory edits are gone
            if (table != null && !dirty.isEmpty()) { msgBad = true; msg("Tile table reloaded from disk: unsaved editor changes were dropped."); }
            table = t;
            undo.clear();
            redo.clear();
            dirty.clear();
            geo = null;
        }
        if (needRebuild) {
            r3d.rebuildMesh();
            needRebuild = false;
        }
        MapSnapshot m = r3d.currentMap();
        if (m != null && (geo == null || geo.map != m || geo.tableVersion != table.version())) {
            geo = new PickGeometry(m, table);
            if (lastMapId != m.mapId) {
                if (lastMapId != -2) selHas = false;
                lastMapId = m.mapId;
                palScroll = 0;
            }
            if (selHas && selCell) selectCell(selLayer, selX, selY); // refresh depth etc. after rebuilds
        }
        camCombined = r3d.camera().combined;
        vpRect = r3d.viewport();
        float[] ms = mouse();
        float mx = ms[0], my = ms[1];

        // ---- hover ----
        hover = null;
        hoverPalTile = paletteTileAt(mx, my);
        if (geo != null && !overPanel(mx, my) && in(mx, my, vpRect[0], vpRect[1], vpRect[2], vpRect[3])) {
            float nx = 2 * (mx - vpRect[0]) / vpRect[2] - 1, ny = 2 * (my - vpRect[1]) / vpRect[3] - 1;
            com.badlogic.gdx.math.Matrix4 inv = r3d.camera().invProjectionView;
            Vector3 o = new Vector3(nx, ny, -1).prj(inv), f = new Vector3(nx, ny, 1).prj(inv);
            Vector3 d = f.sub(o).nor();
            PickGeometry.Hit h = geo.pick(o, d);
            if (h != null) hover = h.quad;
        }

        Gdx.gl.glViewport(0, 0, bw, bh);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawHighlights(bw, bh);
        layoutUi(bw, bh);
        flush(bw, bh);
    }

    // ---------------------------------------------------------------------------------------------- highlights

    private float[] poly = new float[8];

    private void projectQuad(PickGeometry.Quad q) {
        Vector3 v = new Vector3();
        for (int i = 0; i < 4; i++) {
            v.set(q.p[i * 3], q.p[i * 3 + 1], q.p[i * 3 + 2]).prj(camCombined);
            poly[i * 2] = vpRect[0] + (v.x + 1) / 2 * vpRect[2];
            poly[i * 2 + 1] = vpRect[1] + (v.y + 1) / 2 * vpRect[3];
        }
    }

    private void drawHighlights(int bw, int bh) {
        if (geo == null) return;
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // all uses of the selected tile, faint
        if (selHas && selTile >= 0) {
            shapes.setColor(0.2f, 0.9f, 1f, 0.22f);
            for (PickGeometry.Quad q : geo.quads) {
                if (q.layer == selLayer && q.tile == selTile) {
                    projectQuad(q);
                    shapes.triangle(poly[0], poly[1], poly[2], poly[3], poly[4], poly[5]);
                    shapes.triangle(poly[0], poly[1], poly[4], poly[5], poly[6], poly[7]);
                }
            }
        }
        if (hover != null) {
            shapes.setColor(1f, 0.9f, 0.1f, 0.35f);
            for (PickGeometry.Quad q : geo.quads) {
                if (q.layer == hover.layer && q.cx == hover.cx && q.cy == hover.cy) {
                    projectQuad(q);
                    shapes.triangle(poly[0], poly[1], poly[2], poly[3], poly[4], poly[5]);
                    shapes.triangle(poly[0], poly[1], poly[4], poly[5], poly[6], poly[7]);
                }
            }
        }
        float lw = 2f * ui;
        for (int pass = 0; pass < 2; pass++) {
            boolean selPass = pass == 1;
            if (selPass && !(selHas && selCell)) continue;
            if (selPass) shapes.setColor(0.2f, 1f, 1f, 1f);
            else if (hover != null) shapes.setColor(1f, 0.95f, 0.2f, 1f);
            else continue;
            int layer = selPass ? selLayer : hover.layer, cx = selPass ? selX : hover.cx, cy = selPass ? selY : hover.cy;
            for (PickGeometry.Quad q : geo.quads) {
                if (q.layer != layer || q.cx != cx || q.cy != cy) continue;
                projectQuad(q);
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    shapes.rectLine(poly[i * 2], poly[i * 2 + 1], poly[j * 2], poly[j * 2 + 1], lw);
                }
            }
        }
        shapes.end();
    }

    // ---------------------------------------------------------------------------------------------- UI

    private void rect(int z, float x, float y, float w, float h, Color c, boolean outline) {
        Cmd d = new Cmd();
        d.z = z;
        d.x = x;
        d.y = y;
        d.w = w;
        d.h = h;
        d.c = c;
        d.outline = outline;
        cmds.add(d);
    }

    private void text(String s, float x, float y, Color c, float scale) {
        Cmd d = new Cmd();
        d.z = 3;
        d.s = s;
        d.x = x;
        d.y = y;
        d.c = c;
        d.scale = scale;
        cmds.add(d);
    }

    private static final Color BG = new Color(0.05f, 0.06f, 0.09f, 0.82f), WHITE = Color.WHITE, GRAY = new Color(0.7f, 0.72f, 0.78f, 1),
            YELLOW = new Color(1f, 0.92f, 0.3f, 1), CYAN = new Color(0.4f, 1f, 1f, 1), RED = new Color(1f, 0.4f, 0.35f, 1),
            GREEN = new Color(0.5f, 1f, 0.5f, 1), BTN = new Color(0.2f, 0.24f, 0.34f, 1), BTN_ON = new Color(0.25f, 0.5f, 0.8f, 1);

    private void button(String label, float x, float y, float w, float h, boolean on, Runnable a) {
        rect(0, x, y, w, h, on ? BTN_ON : BTN, false);
        rect(2, x, y, w, h, new Color(1, 1, 1, 0.25f), true);
        text(label, x + 6 * ui, y + h - 3 * ui, WHITE, ui);
        Button b = new Button();
        b.x = x;
        b.y = y;
        b.w = w;
        b.h = h;
        b.action = a;
        nextButtons.add(b);
    }

    private static String shortName(File f) {
        if (f == null) return "?";
        String p = f.getPath();
        String home = System.getProperty("user.home");
        return home != null && p.startsWith(home) ? "~" + p.substring(home.length()) : p;
    }

    private String describeTile(int layer, int tile, int tilesetId, int depth, int x, int y, boolean cell) {
        Resolved r = table.resolve(geo.map.mapId, layer, tilesetId, tile, depth, cell ? x : NO_CELL, cell ? y : NO_CELL);
        return r.shape + "   <- " + r.level.name().toLowerCase().replace('_', ' ') + ": " + r.source;
    }

    private void layoutUi(int bw, int bh) {
        cmds.clear();
        nextButtons.clear();
        float lh = 18 * ui, pad = 8 * ui, w = 430 * ui;
        float x = 10 * ui, top = bh - 10 * ui;
        // ---- build lines first to know the height
        List<Object[]> lines = new ArrayList<>(); // {text, color}
        boolean dirtyNow = !dirty.isEmpty();
        MapSnapshot m = geo == null ? null : geo.map;
        lines.add(new Object[] {"TILE EDITOR   " + (dirtyNow ? "UNSAVED (" + dirty.size() + ")" : "saved"), dirtyNow ? RED : GREEN});
        if (m != null) {
            lines.add(new Object[] {"map " + m.mapId + "   " + m.w + "x" + m.h + "   tilesets L0=" + m.tileset0 + " L1=" + m.tileset1, WHITE});
            lines.add(new Object[] {"file: " + shortName(table.saveTarget()) + (table.saveTargetIsUserFile() ? " (user)" : " (source)"), GRAY});
        }
        lines.add(new Object[] {"-- hover --", YELLOW});
        if (hoverPalTile >= 0 && m != null) {
            int ts = palLayer == 0 ? m.tileset0 : m.tileset1;
            lines.add(new Object[] {"palette L" + palLayer + " tile " + hoverPalTile + "  (used " + (palLayer == 0 ? geo.used0 : geo.used1)[hoverPalTile] + "x here)", WHITE});
            lines.add(new Object[] {describeTile(palLayer, hoverPalTile, ts, -1, 0, 0, false), GRAY});
        } else if (hover != null) {
            lines.add(new Object[] {String.format("cell (%d,%d)  layer %d  tile %d  xf %d%s", hover.cx, hover.cy, hover.layer, hover.tile, hover.transform,
                    hover.layer == 1 ? "  depth d" + hover.depth + " (base row " + hover.baseRow + ")" : ""), WHITE});
            lines.add(new Object[] {describeTile(hover.layer, hover.tile, hover.layer == 0 ? m.tileset0 : m.tileset1, hover.depth, hover.cx, hover.cy, true), GRAY});
        } else {
            lines.add(new Object[] {"(point at a tile)", GRAY});
        }
        lines.add(new Object[] {"-- selected --", CYAN});
        if (selHas && m != null) {
            if (selCell) {
                lines.add(new Object[] {String.format("cell (%d,%d)  layer %d (Tab: other)  tile %s  xf %d%s", selX, selY, selLayer, selTile < 0 ? "none" : String.valueOf(selTile),
                        selLayer == 0 ? geo.transform0(selX, selY) : geo.transform1(selX, selY), selLayer == 1 && selDepth >= 0 ? "  depth d" + selDepth : ""), WHITE});
            } else {
                lines.add(new Object[] {"palette tile " + selTile + "  layer " + selLayer, WHITE});
            }
            if (selTile >= 0) lines.add(new Object[] {describeTile(selLayer, selTile, selTileset(), selDepth, selX, selY, selCell), GRAY});
        } else {
            lines.add(new Object[] {"(nothing; click a tile)", GRAY});
        }
        float h = pad * 2 + lines.size() * lh + 156 * ui + 3.6f * lh; // 156 = scope row + 3 kind rows + action row + status
        panelX = x - 4 * ui;
        panelW = w;
        panelH = h;
        panelY = top - h;
        rect(0, panelX, panelY, panelW, panelH, BG, false);
        float ty = top - pad;
        for (Object[] l : lines) {
            text((String) l[0], x + pad / 2, ty, (Color) l[1], ui);
            ty -= lh;
        }
        ty -= 6 * ui;
        // scope row
        text("scope (M):", x + pad / 2, ty, GRAY, ui);
        float sx = x + 90 * ui;
        for (Scope s : Scope.values()) {
            float bw2 = 100 * ui;
            final Scope sc = s;
            button(s.label, sx, ty - 20 * ui, bw2, 22 * ui, scope == s, () -> scope = sc);
            sx += bw2 + 6 * ui;
        }
        ty -= 28 * ui;
        // shape buttons
        Shape cur = selHas && selTile >= 0 && geo != null ? resolveSel().shape : null;
        float bwid = (w - 2 * pad - 3 * 6 * ui) / 4;
        int rows = (KINDS.length + 1 + 3) / 4; // kind buttons + one slot for -/+
        for (int i = 0; i < KINDS.length; i++) {
            int col = i % 4, row = i / 4;
            final Kind k = KINDS[i];
            float bx = x + col * (bwid + 6 * ui), by = ty - row * 26 * ui - 22 * ui;
            button(kindKey(i) + " " + k.name().toLowerCase(), bx, by, bwid, 22 * ui, cur != null && cur.kind == k, () -> setKind(k));
            rect(2, bx, by, 4 * ui, 22 * ui, kindColor(k), false);
        }
        int ms = KINDS.length; // slot after the last kind
        float bx = x + ms % 4 * (bwid + 6 * ui), by = ty - ms / 4 * 26 * ui - 22 * ui;
        button("-", bx, by, bwid / 2 - 3 * ui, 22 * ui, false, () -> adjust(-1));
        button("+", bx + bwid / 2 + 3 * ui, by, bwid / 2 - 3 * ui, 22 * ui, false, () -> adjust(1));
        ty -= rows * 26 * ui + 4 * ui;
        // action row
        float aw = (w - 2 * pad - 4 * 6 * ui) / 5;
        float ax = x;
        final String[] names = {"Undo", "Redo", "Del entry", "Palette (P)", "Save"};
        final Runnable[] acts = {this::undo, this::redo, this::removeEntry, () -> showPalette = !showPalette, this::save};
        for (int i = 0; i < 5; i++) {
            button(names[i], ax, ty - 22 * ui, aw, 22 * ui, i == 3 && showPalette, acts[i]);
            ax += aw + 6 * ui;
        }
        ty -= 30 * ui;
        // status + hints
        if (System.currentTimeMillis() < msgUntil) {
            Color c = msg.startsWith("UNSAVED") || msg.startsWith("SAVE FAILED") ? RED : YELLOW;
            String rest = msg;
            for (int ln = 0; ln < 2 && !rest.isEmpty(); ln++) {
                int cut = rest.length();
                if (cut > 64) {
                    cut = rest.lastIndexOf(' ', 64);
                    if (cut < 20) cut = 64;
                }
                text(rest.substring(0, cut), x + pad / 2, ty - ln * lh * 0.9f, c, ui * 0.9f);
                rest = rest.substring(cut).trim();
            }
        }
        ty -= lh * 2;
        text("click select | 1-9,0,F shape | +/- or Ctrl+wheel param (Alt: rise) | Tab layer | arrows move", x + pad / 2, ty, GRAY, ui * 0.78f);
        ty -= lh * 0.85f;
        text("Ctrl+Z/Y undo/redo | Ctrl+S save | Del remove | M scope | P palette | F7 close", x + pad / 2, ty, GRAY, ui * 0.78f);
        layoutPalette(bw, bh);
        buttons = new ArrayList<>(nextButtons);
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }

    private boolean palScrollable() { return showPalette && palScale > 0 && palImage != null && palImage.getHeight() * palScale > palH - 60; }

    private int paletteTileAt(float mx, float my) {
        if (!showPalette || palImage == null || palScale <= 0) return -1;
        float lx = (mx - palSheetX) / palScale, ly = (palSheetY - my) / palScale; // sheet pixels, y down from the sheet top
        if (lx < 0 || ly < 0 || lx >= palImage.getWidth() || ly >= palImage.getHeight()) return -1;
        if (!in(mx, my, palX, palY, palW, palH)) return -1;
        MapSnapshot m = geo == null ? null : geo.map;
        if (m == null) return -1;
        int tw = m.tileW, th = m.tileH;
        int id = (int) (ly / th) * palCols + (int) (lx / tw);
        return id < palCols * palRows && id < 256 ? id : -1;
    }

    private Texture texture(Image im) {
        Texture t = palTex.get(im);
        if (t == null) {
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getPixelsInternal();
            Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            java.nio.ByteBuffer bb = pm.getPixels();
            for (int i = 0; i < w * h; i++) {
                int p = px[i];
                bb.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            }
            bb.flip();
            t = new Texture(pm);
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            pm.dispose();
            palTex.put(im, t);
        }
        return t;
    }

    private void layoutPalette(int bw, int bh) {
        palImage = null;
        palScale = 0;
        if (!showPalette || geo == null) return;
        MapSnapshot m = geo.map;
        int layer = palLayer;
        Image im = layer == 0 ? m.sheet0 : m.sheet1;
        int ts = layer == 0 ? m.tileset0 : m.tileset1;
        if (im == null) return;
        palCols = layer == 0 ? m.cols0 : m.cols1;
        palRows = layer == 0 ? m.rows0 : m.rows1;
        palImage = im;
        float lh = 18 * ui, pad = 8 * ui;
        float maxW = bw * 0.46f, maxH = bh - 20 * ui - 4 * lh - 70 * ui;
        float s = Math.min(maxW / im.getWidth(), maxH / im.getHeight());
        s = s >= 1 ? Math.min(4f, (float) Math.floor(s)) : Math.max(0.5f, s);
        palScale = s;
        float sheetW = im.getWidth() * s, sheetH = im.getHeight() * s;
        palW = Math.max(sheetW + 2 * pad, 430 * ui);
        palH = Math.min(bh - 20 * ui, sheetH + 2 * pad + 4 * lh + 14 * ui); // title, hint, 2 legend lines
        palX = bw - palW - 10 * ui;
        palY = bh - 10 * ui - palH;
        rect(0, palX, palY, palW, palH, BG, false);
        float ty = palY + palH - pad;
        text("PALETTE  layer " + layer + "  tileset " + ts + "  (Tab layer, wheel scroll)", palX + pad, ty, WHITE, ui);
        ty -= lh;
        text("badge = shape, yellow frame = used on this map", palX + pad, ty, GRAY, ui * 0.85f);
        ty -= lh;
        // legend
        float lx = palX + pad;
        for (int i = 0; i < KINDS.length; i++) {
            rect(2, lx, ty - 12 * ui, 10 * ui, 10 * ui, kindColor(KINDS[i]), false);
            String n = KINDS[i].name().toLowerCase();
            text(n, lx + 13 * ui, ty, WHITE, ui * 0.8f);
            lx += (13 + n.length() * 6.2f + 8) * ui * 0.8f * 1.25f;
            if (i + 1 < KINDS.length && lx + (13 + KINDS[i + 1].name().length() * 6.2f) * ui * 0.8f * 1.25f > palX + palW - pad) {
                lx = palX + pad; // wrap
                ty -= lh * 0.85f;
            }
        }
        ty -= lh + 2 * ui;
        // sheet
        float availH = ty - (palY + pad);
        float maxScroll = Math.max(0, sheetH - availH);
        float stepPx = m.tileH * s;
        int maxSteps = (int) Math.ceil(maxScroll / stepPx);
        palScroll = Math.max(0, Math.min(maxSteps, palScroll));
        float scroll = Math.min(maxScroll, palScroll * stepPx);
        palSheetX = palX + pad;
        palSheetY = ty + scroll; // y of the sheet's top edge (window coords, y up)
        Cmd img = new Cmd();
        img.z = 1;
        img.t = texture(im);
        img.x = palSheetX;
        img.y = palSheetY - sheetH;
        img.w = sheetW;
        img.h = sheetH;
        img.c = new Color(0.24f, 0.36f, 0.24f, 1f);
        cmds.add(img);
        int[] used = layer == 0 ? geo.used0 : geo.used1;
        float tw = m.tileW * s, th = m.tileH * s;
        float clipTop = ty, clipBot = palY + pad;
        for (int id = 0; id < palCols * palRows && id < 256; id++) {
            float tx = palSheetX + id % palCols * tw, tyy = palSheetY - (id / palCols + 1) * th;
            if (tyy + th > clipTop + 0.5f || tyy < clipBot - 0.5f) continue;
            Resolved r = table.resolve(m.mapId, layer, ts, id, -1, NO_CELL, NO_CELL);
            boolean sel = selHas && !selCell && selLayer == layer && selTile == id || selHas && selCell && selLayer == layer && selTile == id;
            if (used[id] == 0) rect(2, tx, tyy, tw, th, new Color(0, 0, 0, 0.6f), false);
            Color kc = kindColor(r.shape.kind);
            float bsz = Math.max(6f, tw * 0.28f);
            boolean isDefault = r.level == Level.DEFAULT;
            rect(2, tx + tw - bsz, tyy + th - bsz, bsz, bsz, new Color(kc.r, kc.g, kc.b, isDefault ? 0.55f : 1f), false);
            if (!isDefault) rect(2, tx + tw - bsz, tyy + th - bsz, bsz, bsz, new Color(1, 1, 1, 0.9f), true);
            if (used[id] > 0) rect(2, tx, tyy, tw, th, new Color(1f, 0.9f, 0.2f, 0.85f), true);
            else rect(2, tx, tyy, tw, th, new Color(1, 1, 1, 0.12f), true);
            if (sel) {
                rect(2, tx - 1, tyy - 1, tw + 2, th + 2, CYAN, true);
                rect(2, tx + 1, tyy + 1, tw - 2, th - 2, CYAN, true);
            }
            if (id == hoverPalTile) rect(2, tx, tyy, tw, th, WHITE, true);
            if (tw >= 22 * ui * 0.9f) text(String.valueOf(id), tx + 2, tyy + th - 1, WHITE, ui * 0.7f);
        }
    }

    private void flush(int bw, int bh) {
        // fills + outlines by z
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        for (int z = 0; z <= 3; z++) {
            if (z == 1) {
                batch.begin();
                for (Cmd d : cmds) {
                    if (d.z != 1) continue;
                    // background under (transparent) tiles
                    batch.setColor(d.c);
                    batch.draw(rectTex(), d.x, d.y, d.w, d.h);
                    batch.setColor(Color.WHITE);
                    batch.draw(d.t, d.x, d.y, d.w, d.h);
                }
                batch.end();
            } else if (z == 3) {
                batch.begin();
                float last = -1;
                for (Cmd d : cmds) {
                    if (d.z != 3) continue;
                    if (d.scale != last) { font.getData().setScale(d.scale); last = d.scale; }
                    font.setColor(0, 0, 0, 0.9f);
                    font.draw(batch, d.s, d.x + 1, d.y - 1);
                    font.setColor(d.c);
                    font.draw(batch, d.s, d.x, d.y);
                }
                batch.end();
            } else {
                // z 0: filled; z 2: filled (non outline) then outlines
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                for (Cmd d : cmds) {
                    if (d.z != z || d.outline) continue;
                    shapes.setColor(d.c);
                    shapes.rect(d.x, d.y, d.w, d.h);
                }
                shapes.end();
                if (z == 2) {
                    Gdx.gl.glLineWidth(Math.max(1f, ui));
                    shapes.begin(ShapeRenderer.ShapeType.Line);
                    for (Cmd d : cmds) {
                        if (d.z != 2 || !d.outline) continue;
                        shapes.setColor(d.c);
                        shapes.rect(d.x, d.y, d.w, d.h);
                    }
                    shapes.end();
                }
            }
        }
        font.getData().setScale(1f);
    }

    private Texture whiteTex;

    private Texture rectTex() {
        if (whiteTex == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            whiteTex = new Texture(pm);
            pm.dispose();
        }
        return whiteTex;
    }

    public void dispose() {
        if (batch != null) batch.dispose();
        if (shapes != null) shapes.dispose();
        if (font != null) font.dispose();
        if (whiteTex != null) whiteTex.dispose();
        for (Texture t : palTex.values()) t.dispose();
        palTex.clear();
    }
}
