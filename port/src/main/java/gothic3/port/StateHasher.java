package gothic3.port;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Verification tool: hashes the logical game state = every static field of the g3 classes (arrays and
 * collections deep, strings by value, the RNG by its serialized state). Fields that are pure render
 * caches / scratch buffers or owned by background threads are listed in {@link #EXCLUDED} and are
 * not part of the hash (see docs/analysis/render-side-effects.md).
 */
public final class StateHasher {
    private StateHasher() {}

    private static final String[] CLASSES = {"g3.GameEngine", "g3.GameWorld", "g3.Gothic3Midlet", "g3.LoadTask", "g3.SoundThread"};

    /** Static fields that are not game logic: image/model caches filled lazily by draw code, bg scroll cache, load progress. */
    private static final java.util.Set<String> EXCLUDED = new java.util.HashSet<>(java.util.Arrays.asList(
            // lazily loaded image / model sheet caches (pure functions of resource files)
            // 3D capture area bookkeeping, written by the world pass only (docs/render3d.md "Capture area")
            "GameEngine.portExtraOn", "GameEngine.portWx0", "GameEngine.portWy0", "GameEngine.portWx1", "GameEngine.portWy1", "GameEngine.portExt",
            "GameEngine.portWinIds", "GameEngine.portWinLinks", "GameEngine.portExtraIds", "GameEngine.portExtraRows",
            "GameEngine.portWinN", "GameEngine.portExtraN", "GameEngine.portAnim", "GameEngine.portAnimEnt", "GameEngine.portAnimMap",
            "GameEngine.images", "GameEngine.imageFrames", "GameEngine.imageTable", "GameEngine.sheetImages", "GameEngine.sheetPngBytes",
            "GameEngine.rawImageBytes", "GameEngine.rawOffsetBytes", "GameEngine.packStream", "GameEngine.packEntryLength",
            "GameEngine.packCurrentImage", "GameEngine.packBuffer", "GameEngine.crcTable",
            // background scroll buffer cache (position of the window currently held by bgBuffer)
            "GameEngine.bgTileX", "GameEngine.bgTileY", "GameEngine.bgDirty", "GameEngine.bgBuffer", "GameEngine.bgGraphics",
            // real (render) camera: presentation only since widescreen, gameplay uses logicCamX/Y (virtual 240x320 view)
            "GameEngine.cameraX", "GameEngine.cameraY", "GameEngine.cameraTargetX", "GameEngine.cameraTargetY",
            // layout caches that legitimately depend on the logical screen size (widescreen presets): sizes, text areas,
            // wrapped dialogue/message text and widget geometry. Gameplay state (player, npcs, dialogue progress, logic camera) stays hashed.
            "GameEngine.screenWidth", "GameEngine.screenHeight", "GameEngine.textAreaRects", "GameEngine.bgTilesW", "GameEngine.bgTilesH",
            "GameEngine.dialogueRectX", "GameEngine.dialogueRectW", "GameEngine.dialogueRectH", "GameEngine.dialogueLines",
            "GameEngine.dialogueContentH", "GameEngine.dialogueHistory", "GameEngine.quickslotPos", "GameEngine.quickslotLabelX",
            "GameEngine.quickslotLabelY", "GameEngine.quickslotLabelW", "GameEngine.quickslotLabelH", "GameEngine.quickslotGapX",
            "GameEngine.quickslotGapY", "GameEngine.targetBarX", "GameEngine.targetBarW", "GameEngine.targetBarH", "GameEngine.targetBarFill",
            "GameEngine.msgBoxX", "GameEngine.msgBoxW", "GameEngine.msgBoxTop", "GameEngine.msgBoxHeight", "GameEngine.messageLines",
            "Gothic3Midlet.contentW", "Gothic3Midlet.contentH",
            "GameEngine.frameStartTime", "GameEngine.gameThread",
            // written by the background loader thread while it runs
            "LoadTask.progress"
    ));

    private static final List<Field> FIELDS = new ArrayList<>();
    private static boolean init;

    private static synchronized void init() {
        if (init) return;
        init = true;
        for (String cn : CLASSES) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, GameHooks.bridge().gameClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                } catch (RuntimeException e) {
                    continue;
                }
                FIELDS.add(f);
            }
        }
    }

    public static String name(Field f) { return f.getDeclaringClass().getSimpleName() + "." + f.getName(); }

    /** Per-field hashes in declaration order (name -> hash), excluded fields omitted. */
    public static Map<String, Long> fieldHashes() {
        init();
        Map<String, Long> out = new TreeMap<>();
        for (Field f : FIELDS) {
            String n = name(f);
            if (EXCLUDED.contains(n)) continue;
            try {
                out.put(n, hashValue(f.get(null)));
            } catch (Throwable t) {
                out.put(n, -1L);
            }
        }
        return out;
    }

    /** One hash over all (non-excluded) fields. */
    public static long total() {
        long h = 1125899906842597L;
        for (Map.Entry<String, Long> e : fieldHashes().entrySet()) {
            h = mix(h, e.getKey().hashCode());
            h = mix(h, e.getValue());
        }
        return h;
    }

    static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 31);
    }

    /**
     * The serialized RNG contains its class name. The class used to be {@code g3.port.SnapshotRandom}; rewrite the name in the
     * byte stream so that state hashes (and thus recorded traces) stay comparable with traces made before the move to gothic3.port.
     */
    private static byte[] legacyName(byte[] b, Object o) {
        String now = o.getClass().getName();
        if (!now.equals("gothic3.port.SnapshotRandom")) return b;
        byte[] n = now.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] old = "g3.port.SnapshotRandom".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i + 2 + n.length <= b.length; i++) {
            if (b[i] != 0 || (b[i + 1] & 0xFF) != n.length) continue;
            boolean m = true;
            for (int k = 0; k < n.length && m; k++) m = b[i + 2 + k] == n[k];
            if (!m) continue;
            byte[] out = new byte[b.length - n.length + old.length];
            System.arraycopy(b, 0, out, 0, i);
            out[i] = 0;
            out[i + 1] = (byte) old.length;
            System.arraycopy(old, 0, out, i + 2, old.length);
            System.arraycopy(b, i + 2 + n.length, out, i + 2 + old.length, b.length - i - 2 - n.length);
            return out;
        }
        return b;
    }

    public static long hashValue(Object o) {
        return hash(o, new IdentityHashMap<>(), 0);
    }

    private static long hash(Object o, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (o == null) return 7;
        if (depth > 12) return 11;
        Class<?> c = o.getClass();
        if (c == String.class || o instanceof Number || o instanceof Boolean || o instanceof Character) return o.hashCode();
        if (c.isArray()) {
            Class<?> ct = c.getComponentType();
            if (ct == int.class) return java.util.Arrays.hashCode((int[]) o);
            if (ct == short.class) return java.util.Arrays.hashCode((short[]) o);
            if (ct == byte.class) return java.util.Arrays.hashCode((byte[]) o);
            if (ct == boolean.class) return java.util.Arrays.hashCode((boolean[]) o);
            if (ct == long.class) return java.util.Arrays.hashCode((long[]) o);
            if (ct == char.class) return java.util.Arrays.hashCode((char[]) o);
            if (ct.isPrimitive()) return 13;
            if (seen.put(o, Boolean.TRUE) != null) return 17;
            long h = 31 + Array.getLength(o);
            for (Object e : (Object[]) o) h = mix(h, hash(e, seen, depth + 1));
            return h;
        }
        if (o instanceof Random) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (ObjectOutputStream oo = new ObjectOutputStream(bo)) {
                    oo.writeObject(o);
                }
                return java.util.Arrays.hashCode(legacyName(bo.toByteArray(), o));
            } catch (Exception e) {
                return -2;
            }
        }
        if (o instanceof Collection) {
            if (seen.put(o, Boolean.TRUE) != null) return 17;
            long h = 41;
            for (Object e : new ArrayList<>((Collection<?>) o)) h = mix(h, hash(e, seen, depth + 1));
            return h;
        }
        if (o instanceof Dictionary) {
            if (seen.put(o, Boolean.TRUE) != null) return 17;
            long h = 43;
            Dictionary<?, ?> d = (Dictionary<?, ?>) o;
            java.util.Enumeration<?> k = d.keys();
            while (k.hasMoreElements()) {
                Object key = k.nextElement();
                h += mix(hash(key, seen, depth + 1), hash(d.get(key), seen, depth + 1)); // order independent
            }
            return h;
        }
        // Images, fonts, streams, threads ...: identity is not state. Only the class counts.
        return c.getName().hashCode();
    }
}
