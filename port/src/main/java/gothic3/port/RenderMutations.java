package gothic3.port;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The few pieces of game state that the (decompiled) render code itself writes. The canonical frame after a
 * tick runs unmodified and mutates them as in the original. Every extra in-between frame must see exactly the
 * values the canonical frame saw and must leave the post-canonical values behind:
 *
 * <pre>
 *   capturePre()   (before the canonical render)   - the values the canonical render starts from
 *   render         (canonical)
 *   capturePost()  (after the canonical render)    - the values game logic continues with
 *   ...per in-between frame:  applyPre(); render; applyPost();
 * </pre>
 *
 * This is the "snapshot/restore" form of making in-between frames side-effect free: the frame is a function
 * of the pre-render state (so it is identical to the canonical one at alpha 1), and logic never sees what
 * the extra render did. The set is listed in docs/analysis/render-side-effects.md and verified at run time
 * by {@code -Dg3.verify=true}.
 */
public final class RenderMutations {
    private RenderMutations() {}

    /** Static fields (arrays are compared/copied by content) written by draw code. "Class.field". */
    private static final String[] STATICS = {
        // text-area marquee scroll position / direction: advanced after being drawn (drawLinesInArea)
        "GameEngine.textAreaScroll", "GameEngine.textAreaScrollDir",
        // scratch vectors / rectangles used by draw helpers (also used by logic: always written before read)
        "GameEngine.animBoundsOut", "GameEngine.shadowPos", "GameEngine.scratchPos3",
        "GameEngine.worldMapPlayerPos", "GameEngine.xD",
        "GameWorld.scratchPosHitbox", "GameWorld.scratchPosSort", "GameWorld.scratchRectSort", "GameWorld.scratchPosTile",
    };
    // Entity rows: the whole row of every entity is swapped (cheap: a few thousand ints). Draw code writes [1] (sorted-render chain,
    // high 16 bits), [9]/[10] (lazy model-sheet slot defaults, world object animation frame/counter); swapping complete rows
    // also covers anything else a draw path may touch. Callers must apply the pre/post rows BEFORE TickInterpolation.begin and
    // AFTER TickInterpolation.end, because the rows contain the position words as well.

    private static Field[] fields;
    private static Object[] pre, post;
    private static int[][] entPre = new int[0][], entPost = new int[0][];
    private static int[][] entTable;

    private static synchronized void init() {
        if (fields != null) return;
        List<Field> l = new ArrayList<>();
        for (String s : STATICS) {
            int dot = s.indexOf('.');
            try {
                Class<?> c = Class.forName("g3." + s.substring(0, dot), false, GameHooks.bridge().gameClassLoader());
                Field f = c.getDeclaredField(s.substring(dot + 1));
                f.setAccessible(true);
                l.add(f);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("[port] RenderMutations: no field " + s + " (" + e + ")");
            }
        }
        fields = l.toArray(new Field[0]);
        pre = new Object[fields.length];
        post = new Object[fields.length];
    }

    private static Object copyOf(Object v) {
        if (v == null) return null;
        if (v instanceof int[]) return ((int[]) v).clone();
        if (v instanceof short[]) return ((short[]) v).clone();
        if (v instanceof byte[]) return ((byte[]) v).clone();
        if (v instanceof boolean[]) return ((boolean[]) v).clone();
        if (v instanceof long[]) return ((long[]) v).clone();
        return v; // immutable scalar / String
    }

    private static void store(Field f, Object saved) {
        try {
            Object cur = f.get(null);
            if (saved != null && cur != null && cur.getClass().isArray() && java.lang.reflect.Array.getLength(cur) == java.lang.reflect.Array.getLength(saved)) {
                System.arraycopy(saved, 0, cur, 0, java.lang.reflect.Array.getLength(saved)); // keep identity of the array
            } else {
                f.set(null, copyOf(saved));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int[][] copyRows(int[][] ent, int[][] dst) {
        if (dst.length != ent.length) dst = new int[ent.length][];
        for (int i = 0; i < ent.length; i++) {
            int[] e = ent[i];
            if (dst[i] == null || dst[i].length != e.length) dst[i] = new int[e.length];
            System.arraycopy(e, 0, dst[i], 0, e.length);
        }
        return dst;
    }

    private static void capture(Object[] dst, int[][] ent, boolean isPre) {
        init();
        try {
            for (int i = 0; i < fields.length; i++) dst[i] = copyOf(fields[i].get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (ent != null) {
            if (isPre) entPre = copyRows(ent, entPre);
            else entPost = copyRows(ent, entPost);
        }
    }

    private static void apply(Object[] src, int[][] ent, int[][] rows) {
        init();
        for (int i = 0; i < fields.length; i++) store(fields[i], src[i]);
        if (ent != null && rows.length == ent.length) {
            for (int i = 0; i < ent.length; i++) System.arraycopy(rows[i], 0, ent[i], 0, rows[i].length);
        }
    }

    // RNG state is handled here as well
    private static long rngPre, rngPost;

    private static SnapshotRandom rng() {
        java.util.Random r = GameHooks.bridge().gameRandom();
        return r instanceof SnapshotRandom ? (SnapshotRandom) r : null;
    }

    public static void capturePre(int[][] entities) {
        init();
        entTable = entities;
        capture(pre, entities, true);
        SnapshotRandom r = rng();
        if (r != null) rngPre = r.state();
    }

    public static void capturePost(int[][] entities) {
        init();
        capture(post, entities, false);
        SnapshotRandom r = rng();
        if (r != null) rngPost = r.state();
    }

    /** In-between frame start: rewind to what the canonical render started from. */
    public static void applyPre(int[][] entities) {
        if (entities != entTable) return;
        apply(pre, entities, entPre);
        SnapshotRandom r = rng();
        if (r != null) r.state(rngPre);
    }

    /** In-between frame end: put back what the canonical render left. */
    public static void applyPost(int[][] entities) {
        if (entities != entTable) return;
        apply(post, entities, entPost);
        SnapshotRandom r = rng();
        if (r != null) r.state(rngPost);
    }

    // ---- per-entity guard for the extra 3D capture area (GameEngine.portDrawEntity) ----
    // Entities outside the game's own 2D cull window are drawn only for the 3D view; the draw of such an entity must leave no
    // trace: the scratch statics and the game RNG are saved before and put back after each of those draws (the entity's own row
    // is restored by the caller after the world pass).
    private static Object[] gArr = new Object[0];
    private static Object[] gSave = new Object[0];
    private static long gRng;

    /** Once per frame (before the first guarded draw): fetch the scratch arrays (they keep their identity). */
    public static void guardPrepare() {
        init();
        if (gArr.length != fields.length) {
            gArr = new Object[fields.length];
            gSave = new Object[fields.length];
        }
        try {
            for (int i = 0; i < fields.length; i++) {
                Object a = fields[i].get(null);
                gArr[i] = a;
                if (a != null && a.getClass().isArray()) {
                    int n = java.lang.reflect.Array.getLength(a);
                    if (gSave[i] == null || java.lang.reflect.Array.getLength(gSave[i]) != n || gSave[i].getClass() != a.getClass()) gSave[i] = copyOf(a);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void guardBegin() {
        for (int i = 0; i < gArr.length; i++) {
            Object a = gArr[i];
            if (a != null && a.getClass().isArray() && gSave[i] != null) System.arraycopy(a, 0, gSave[i], 0, java.lang.reflect.Array.getLength(a));
        }
        SnapshotRandom r = rng();
        if (r != null) gRng = r.state();
    }

    public static void guardEnd() {
        for (int i = 0; i < gArr.length; i++) {
            Object a = gArr[i];
            if (a != null && a.getClass().isArray() && gSave[i] != null) System.arraycopy(gSave[i], 0, a, 0, java.lang.reflect.Array.getLength(a));
        }
        SnapshotRandom r = rng();
        if (r != null) r.state(gRng);
    }
}
