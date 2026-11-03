package gothic3.port;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verification output of the loop:
 * <ul>
 *   <li>{@code -Dg3.trace.file=F}: one line per logic tick: {@code t=<tick> gs=<gameState> s=<state hash> f=<canonical frame hash>}
 *       (+ {@code a1=<alpha-1 frame hash>} when verification is on). Two runs are equivalent iff the files are equal.
 *       {@code -Dg3.trace.fields=true} also writes {@code F.fields} with one hash per static field and tick.</li>
 *   <li>{@code -Dg3.verify=true}: after every tick (while the world is drawn) the loop renders extra frames at alpha 1, 0 and
 *       0.5 and checks that (a) the alpha-1 frame equals the canonical frame pixel for pixel and (b) no hashed game state changed.
 *       It also records which fields the canonical render itself mutates. A report is written at exit to {@code F.verify}
 *       (or stderr).</li>
 * </ul>
 */
public final class TickTrace {
    private TickTrace() {}

    private static final String FILE = System.getProperty("g3.trace.file");
    private static final boolean FIELDS = Boolean.getBoolean("g3.trace.fields");
    private static final boolean VERIFY = Boolean.getBoolean("g3.verify");
    private static PrintWriter out, fieldsOut;
    private static String pendingLine;
    private static long lastTick = -1;
    private static long curState, curFrame, curA1;
    private static int curGs;
    private static boolean haveA1;

    // verification statistics
    private static long parityTicks, parityIdentical, parityMaxDiff;
    private static final TreeMap<String, long[]> intermediateChanged = new TreeMap<>(); // field -> {count, firstTick}
    private static final TreeMap<String, long[]> canonicalChanged = new TreeMap<>();
    private static final TreeMap<Integer, Long> entityWordsChanged = new TreeMap<>();
    private static Map<String, Long> canonBefore;
    private static int[][] entitiesBefore;
    private static long verifyTicks, canonicalFrames;
    private static boolean hookInstalled;
    private static final java.util.Set<Long> DUMP_TICKS = new java.util.HashSet<>();
    private static final String DUMP_DIR = System.getProperty("g3.dump.dir");
    static {
        String d = System.getProperty("g3.dump.ticks");
        if (d != null) for (String t : d.split(",")) if (!t.trim().isEmpty()) DUMP_TICKS.add(Long.parseLong(t.trim()));
    }

    private static void dump(String name, int[] px) {
        if (DUMP_DIR == null) return;
        try {
            int w = gothic3.platform.Platform.getWidth(), h = gothic3.platform.Platform.getHeight();
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            bi.setRGB(0, 0, w, h, px, 0, w);
            File dir = new File(DUMP_DIR);
            dir.mkdirs();
            javax.imageio.ImageIO.write(bi, "png", new File(dir, name + ".png"));
        } catch (Exception e) {
            System.err.println("[trace] dump failed: " + e);
        }
    }

    public static boolean enabled() { return FILE != null; }

    private static final String FRAME_LOG = System.getProperty("g3.frame.log");
    private static PrintWriter frameLog;

    public static boolean frameLogEnabled() { return FRAME_LOG != null; }

    /** One line per rendered world frame: kind (C canonical / I in-between), tick, alpha, camera and player position in pixels. */
    public static synchronized void frame(char kind, long tick, float alpha, int camX, int camY, int playerPxX, int playerPxY) {
        if (FRAME_LOG == null) return;
        try {
            if (frameLog == null) {
                frameLog = new PrintWriter(new BufferedWriter(new FileWriter(FRAME_LOG)));
                frameLog.println("kind tick alpha cameraX cameraY playerX playerY t_ms");
                installHook();
            }
            frameLog.println(kind + " " + tick + " " + String.format("%.3f", alpha) + " " + camX + " " + camY + " " + playerPxX + " " + playerPxY
                    + " " + (System.nanoTime() / 1_000_000L));
        } catch (Exception e) {
            System.err.println("[trace] frame log: " + e);
        }
    }

    public static boolean verifyEnabled() { return VERIFY; }

    private static synchronized void open() {
        if (out != null || FILE == null) return;
        try {
            out = new PrintWriter(new BufferedWriter(new FileWriter(FILE)));
            if (FIELDS) fieldsOut = new PrintWriter(new BufferedWriter(new FileWriter(FILE + ".fields")));
        } catch (Exception e) {
            System.err.println("[trace] cannot open " + FILE + ": " + e);
        }
        installHook();
    }

    private static synchronized void installHook() {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(TickTrace::shutdown, "trace-shutdown"));
    }

    public static long hashPixels(int[] px) {
        long h = 1469598103934665603L;
        for (int v : px) {
            h ^= v & 0xFFFFFFFFL;
            h *= 1099511628211L;
        }
        return h;
    }

    /** Computes the hashes for the tick that just finished (after its canonical render and tickCounter++). */
    public static void tick(long tickNo, int gameState, int[] pixels) {
        if (out == null) open();
        lastTick = tickNo;
        curGs = gameState;
        curA1 = 0;
        haveA1 = false;
        Map<String, Long> fh = StateHasher.fieldHashes();
        long h = 1125899906842597L;
        for (Map.Entry<String, Long> e : fh.entrySet()) {
            h = StateHasher.mix(h, e.getKey().hashCode());
            h = StateHasher.mix(h, e.getValue());
        }
        curState = h;
        curFrame = hashPixels(pixels);
        if (DUMP_TICKS.contains(tickNo)) dump(String.format("canon_%05d", tickNo), pixels);
        if (fieldsOut != null) {
            for (Map.Entry<String, Long> e : fh.entrySet()) fieldsOut.println(tickNo + " " + e.getKey() + " " + Long.toHexString(e.getValue()));
        }
    }

    public static void alpha1Hash(int[] px) {
        curA1 = hashPixels(px);
        haveA1 = true;
    }

    public static void commit() {
        if (out == null) return;
        out.println("t=" + lastTick + " gs=" + curGs + " s=" + Long.toHexString(curState) + " f=" + Long.toHexString(curFrame)
                + (haveA1 ? " a1=" + Long.toHexString(curA1) : ""));
        out.flush();
        if (fieldsOut != null) fieldsOut.flush();
    }

    // ---------------------------------------------------------------- verification

    public static void canonicalBefore() {
        installHook();
        canonBefore = StateHasher.fieldHashes();
        int[][] ent = GameHooks.bridge().entities();
        entitiesBefore = ent == null ? null : deepCopy(ent);
    }

    public static void canonicalAfter() {
        canonicalFrames++;
        Map<String, Long> after = StateHasher.fieldHashes();
        for (Map.Entry<String, Long> e : after.entrySet()) {
            Long b = canonBefore.get(e.getKey());
            if (b == null || !b.equals(e.getValue())) {
                long[] c = canonicalChanged.computeIfAbsent(e.getKey(), k -> new long[] {0, lastTick + 1});
                c[0]++;
            }
        }
        int[][] ent = GameHooks.bridge().entities();
        if (ent != null && entitiesBefore != null && ent.length == entitiesBefore.length) {
            for (int i = 0; i < ent.length; i++) {
                int[] a = entitiesBefore[i], b = ent[i];
                for (int w = 0; w < a.length && w < b.length; w++) {
                    if (a[w] != b[w]) entityWordsChanged.merge(w, 1L, Long::sum);
                }
            }
        }
    }

    private static int[][] deepCopy(int[][] a) {
        int[][] r = new int[a.length][];
        for (int i = 0; i < a.length; i++) r[i] = a[i].clone();
        return r;
    }

    public static void parity(long tick, int[] canonical, int[] alpha1) {
        parityTicks++;
        if (DUMP_TICKS.contains(tick)) dump(String.format("alpha1_%05d", tick), alpha1);
        long diff = 0;
        for (int i = 0; i < canonical.length; i++) if (canonical[i] != alpha1[i]) diff++;
        if (diff == 0) parityIdentical++;
        else {
            parityMaxDiff = Math.max(parityMaxDiff, diff);
            if (diff > 0 && parityTicks - parityIdentical <= 3) System.err.println("[verify] tick " + tick + ": alpha-1 frame differs in " + diff + " pixels");
        }
    }

    public static void intermediateStateDiff(long tick, Map<String, Long> before, Map<String, Long> after) {
        verifyTicks++;
        for (Map.Entry<String, Long> e : after.entrySet()) {
            Long b = before.get(e.getKey());
            if (b == null || !b.equals(e.getValue())) {
                long[] c = intermediateChanged.computeIfAbsent(e.getKey(), k -> new long[] {0, tick});
                c[0]++;
            }
        }
    }

    private static synchronized void shutdown() {
        if (VERIFY) {
            StringBuilder sb = new StringBuilder();
            sb.append("verify report\n");
            sb.append("canonical frames seen: ").append(canonicalFrames).append("\n");
            sb.append("ticks with in-between frames verified: ").append(verifyTicks).append("\n");
            sb.append("alpha=1 frame vs canonical frame: ").append(parityIdentical).append("/").append(parityTicks)
                    .append(" identical (max differing pixels ").append(parityMaxDiff).append(")\n");
            sb.append("game state fields changed by in-between frames (must be empty): ")
                    .append(intermediateChanged.isEmpty() ? "none" : "").append("\n");
            for (Map.Entry<String, long[]> e : intermediateChanged.entrySet())
                sb.append("   ").append(e.getKey()).append(" changed in ").append(e.getValue()[0]).append(" ticks (first ").append(e.getValue()[1]).append(")\n");
            sb.append("fields changed by the canonical render itself (restored/rewound for in-between frames, see RenderMutations):\n");
            for (Map.Entry<String, long[]> e : canonicalChanged.entrySet())
                sb.append("   ").append(e.getKey()).append(" in ").append(e.getValue()[0]).append(" frames (first tick ").append(e.getValue()[1]).append(")\n");
            sb.append("entity row words changed by the canonical render: ").append(entityWordsChanged).append("\n");
            sb.append("interpolation: ").append(TickInterpolation.stats()).append("\n");
            String r = sb.toString();
            System.err.print(r);
            if (FILE != null) {
                try (PrintWriter w = new PrintWriter(new FileWriter(FILE + ".verify"))) {
                    w.print(r);
                } catch (Exception ignored) {
                }
            }
        }
        if (out != null) out.flush();
        if (fieldsOut != null) fieldsOut.flush();
        if (frameLog != null) frameLog.flush();
    }
}
