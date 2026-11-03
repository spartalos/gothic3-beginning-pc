package gothic3.port;

/**
 * Tick-to-tick interpolation of the world state that the renderer reads (camera and the fixed-point
 * positions of mobile entities). Logic stays at 10 Hz; at the end of every tick {@link #capture} stores
 * the new tick values and keeps the previous ones. Any time later a renderer can ask for the state at
 * {@code alpha} in [0,1] between the previous tick (alpha 0) and the current tick (alpha 1):
 *
 * <ul>
 *   <li>read-only queries: {@link #interpolatedX}, {@link #interpolatedY}, {@link #cameraX}, {@link #cameraY}
 *       (no game field is touched; a future 3D renderer uses only these), or</li>
 *   <li>for the unmodified 2D renderer, which reads the game's own fields: {@link #begin} writes the
 *       interpolated values into the entity rows / camera, and {@link #end} puts back the exact tick
 *       values, so game logic never observes interpolated state.</li>
 * </ul>
 *
 * Entities are interpolated only if they are mobile (flag 8: position stored in x=[4], y=[5]; items and
 * world objects keep a template index there), active in both snapshots, of the same kind in both
 * snapshots and moved less than a per-kind distance (otherwise it was a teleport / slot reuse). The
 * whole table is snapshotted without interpolation when the entity table was rebuilt (map change). The
 * camera is not interpolated when the map changed or it jumped by more than a screen.
 *
 * Single-threaded: call everything from the game thread (with the loader lock held for begin/end).
 */
public final class TickInterpolation {
    private TickInterpolation() {}

    /** Entity flag: position is stored as fixed point (4096 per tile) in [4],[5]. */
    public static final int F_FREE_POS = 8, F_PLAYER = 16, F_NPC = 32, F_PROJECTILE = 2048,
            F_SPELLCAST = 16384, F_INACTIVE = 65536, F_FX = 262144;

    /** Max movement per tick (1/4096 tile) that is still interpolated, per kind. Larger = teleport. */
    private static final int LIMIT_ACTOR = 4096, LIMIT_PROJECTILE = 8 * 4096, LIMIT_OTHER = 2 * 4096;
    /** Number of coordinates interpolated per entity: x,y (projectiles also the end point [6],[7]). */
    private static final int STRIDE = 4;

    private static Object epochTable;       // identity of the entity table the snapshots belong to
    private static int epochKey;
    private static int[][] table;           // the entity table of the snapshots
    // per entity: prev/cur coordinate quadruples, kind flags at snapshot time, interpolation allowed
    private static int[] prev = new int[0], cur = new int[0];
    private static int[] curFlags = new int[0];
    private static boolean[] prevValid = new boolean[0], curValid = new boolean[0], blend = new boolean[0];
    private static int camPrevX, camPrevY, camCurX, camCurY;
    private static boolean camBlend;
    private static float alpha = 1f;

    // begin()/end() bookkeeping
    private static boolean active;
    private static int[] saved = new int[0];
    private static int[][] savedTable;

    // statistics
    private static long statCaptured, statBlended, statSkippedJump, statCameraJumps, statEpochs;

    public static String stats() {
        return "captures=" + statCaptured + " blendedEntitiesLastTick=" + statBlended + " entityJumpsSkipped=" + statSkippedJump
                + " cameraJumps=" + statCameraJumps + " epochs=" + statEpochs;
    }

    private static boolean isValid(int flags) {
        if ((flags & F_FREE_POS) == 0) return false;
        // projectiles / effects are pooled; flag 65536 means "not in use". Actors keep 65536 when dead but still have a position.
        if ((flags & (F_PROJECTILE | F_FX)) != 0 && (flags & F_INACTIVE) != 0) return false;
        return true;
    }

    private static int limitFor(int flags) {
        if ((flags & (F_PLAYER | F_NPC)) != 0) return LIMIT_ACTOR;
        if ((flags & F_PROJECTILE) != 0) return LIMIT_PROJECTILE;
        return LIMIT_OTHER;
    }

    private static int kindOf(int flags) {
        return flags & (F_PLAYER | F_NPC | F_PROJECTILE | F_SPELLCAST | F_FX);
    }

    /**
     * End of a logic tick: remember the new tick values and shift the old ones to "previous".
     *
     * @param entities  the game's entity table (rows are the live entity records)
     * @param key       any value that changes when the map changes (area id, map size ...)
     * @param camX,camY camera in pixels after the tick
     * @param screenW   used as the camera-jump threshold
     */
    public static void capture(int[][] entities, int key, int camX, int camY, int screenW) {
        statCaptured++;
        boolean newEpoch = entities != epochTable || key != epochKey;
        if (newEpoch) {
            statEpochs++;
            epochTable = entities;
            epochKey = key;
            int n = entities == null ? 0 : entities.length;
            prev = new int[n * STRIDE];
            cur = new int[n * STRIDE];
            curFlags = new int[n];
            prevValid = new boolean[n];
            curValid = new boolean[n];
            blend = new boolean[n];
            saved = new int[n * STRIDE];
        }
        table = entities;
        int n = entities == null ? 0 : entities.length;
        // shift cur -> prev
        System.arraycopy(cur, 0, prev, 0, cur.length);
        boolean[] t = prevValid;
        prevValid = curValid;
        curValid = t;
        int[] prevFlags = curFlags.clone();
        int blended = 0;
        for (int i = 0; i < n; i++) {
            int[] e = entities[i];
            int f = e[3];
            boolean v = isValid(f);
            curValid[i] = v;
            curFlags[i] = f;
            int o = i * STRIDE;
            if (v) {
                cur[o] = e[4];
                cur[o + 1] = e[5];
                if ((f & F_PROJECTILE) != 0) {
                    cur[o + 2] = e[6];
                    cur[o + 3] = e[7];
                } else {
                    cur[o + 2] = cur[o + 3] = 0;
                }
            } else {
                cur[o] = cur[o + 1] = cur[o + 2] = cur[o + 3] = 0;
            }
            boolean b = false;
            if (!newEpoch && v && prevValid[i] && kindOf(f) == kindOf(prevFlags[i])) {
                int lim = limitFor(f);
                b = Math.abs(cur[o] - prev[o]) <= lim && Math.abs(cur[o + 1] - prev[o + 1]) <= lim;
                if (b && (f & F_PROJECTILE) != 0) {
                    b = Math.abs(cur[o + 2] - prev[o + 2]) <= lim && Math.abs(cur[o + 3] - prev[o + 3]) <= lim;
                }
                if (!b) statSkippedJump++;
            }
            blend[i] = b;
            if (b) blended++;
        }
        statBlended = blended;
        // camera
        camPrevX = camCurX;
        camPrevY = camCurY;
        camCurX = camX;
        camCurY = camY;
        int jump = Math.max(screenW, 64);
        camBlend = !newEpoch && Math.abs(camCurX - camPrevX) <= jump && Math.abs(camCurY - camPrevY) <= jump;
        if (!newEpoch && !camBlend) statCameraJumps++;
    }

    /** Interpolation fraction used by the queries below, clamped to [0,1]. */
    public static void setAlpha(float a) { alpha = a < 0f ? 0f : (a > 1f ? 1f : a); }

    public static float alpha() { return alpha; }

    private static int lerp(int a, int b, float t) {
        return a + Math.round((b - a) * t);
    }

    // ---- read-only queries (valid for the table of the last capture) ----

    /** Interpolated x (1/4096 tile) of entity {@code i} at the current alpha; the tick value if not interpolatable. */
    public static int interpolatedX(int i) { return coord(i, 0); }

    public static int interpolatedY(int i) { return coord(i, 1); }

    /** For projectiles: end point coordinates [6],[7] (k = 2, 3). */
    public static int interpolatedCoord(int i, int k) { return coord(i, k); }

    private static int coord(int i, int k) {
        if (table == null || i < 0 || i >= table.length) return 0;
        if (blend[i]) return lerp(prev[i * STRIDE + k], cur[i * STRIDE + k], alpha);
        int[] e = table[i];
        return e[4 + k];
    }

    public static boolean entityInterpolated(int i) { return table != null && i >= 0 && i < blend.length && blend[i]; }

    public static int cameraX() { return camBlend ? lerp(camPrevX, camCurX, alpha) : camCurX; }

    public static int cameraY() { return camBlend ? lerp(camPrevY, camCurY, alpha) : camCurY; }

    public static boolean cameraInterpolated() { return camBlend; }

    // ---- write-through for the unmodified 2D renderer ----

    /**
     * Writes the interpolated positions of all blendable entities into their rows (the exact tick values
     * are kept aside). Must be paired with {@link #end}. {@code entities} must be the table of the last capture.
     */
    public static void begin(float a, int[][] entities) {
        if (active) throw new IllegalStateException("TickInterpolation.begin without end");
        setAlpha(a);
        if (entities != table || entities == null) {
            active = false;
            savedTable = null;
            return;
        }
        active = true;
        savedTable = entities;
        for (int i = 0; i < entities.length; i++) {
            if (!blend[i]) continue;
            int[] e = entities[i];
            int o = i * STRIDE;
            // exact tick values: they must equal what capture stored; restore() relies on the live row, not on the copy
            saved[o] = e[4];
            saved[o + 1] = e[5];
            e[4] = lerp(prev[o], cur[o], alpha);
            e[5] = lerp(prev[o + 1], cur[o + 1], alpha);
            if ((curFlags[i] & F_PROJECTILE) != 0) {
                saved[o + 2] = e[6];
                saved[o + 3] = e[7];
                e[6] = lerp(prev[o + 2], cur[o + 2], alpha);
                e[7] = lerp(prev[o + 3], cur[o + 3], alpha);
            }
        }
    }

    /** Restores the exact tick values written over by {@link #begin}. */
    public static void end() {
        if (!active) return;
        active = false;
        int[][] entities = savedTable;
        savedTable = null;
        for (int i = 0; i < entities.length; i++) {
            if (!blend[i]) continue;
            int[] e = entities[i];
            int o = i * STRIDE;
            e[4] = saved[o];
            e[5] = saved[o + 1];
            if ((curFlags[i] & F_PROJECTILE) != 0) {
                e[6] = saved[o + 2];
                e[7] = saved[o + 3];
            }
        }
    }

    public static boolean isActive() { return active; }

    /** True if the snapshots belong to this entity table (i.e. no map change since the last capture). */
    public static boolean matches(int[][] entities) { return entities != null && entities == table; }
}
