package gothic3.port;

import java.util.Random;

/**
 * Switches and helpers of the PC port that the (decompiled) game code consults. Everything here is a
 * no-op in the default configuration so that gameplay is unchanged.
 *
 * <ul>
 *   <li>{@code -Dg3.deterministic=true}: game clock = tick count * 100 ms (no wall-clock dependence),
 *       background loads run synchronously on the game thread, fixed RNG seed. Used for tick traces.</li>
 *   <li>{@code -Dg3.fixedSeed=N}: seeds the game RNG with N (implied by deterministic mode: 20080101).</li>
 * </ul>
 */
public final class Port {
    private Port() {}

    public static final boolean DETERMINISTIC = Boolean.getBoolean("g3.deterministic");
    private static final String SEED_PROP = System.getProperty("g3.fixedSeed");

    /** Number of completed logic ticks (game thread writes; only meaningful in deterministic mode). */
    private static volatile long tickIndex;
    private static final long VIRTUAL_EPOCH_MS = 1_700_000_000_000L;

    public static void setTickIndex(long t) { tickIndex = t; }

    /** Replacement for System.currentTimeMillis() in game logic. */
    public static long millis() {
        return DETERMINISTIC ? VIRTUAL_EPOCH_MS + tickIndex * 100L : gothic3.platform.RenderConfig.gameMillis(); // wall clock minus options-screen pauses, scaled by the fast-forward speed
    }

    /**
     * Construction site of the game's RNG (Gothic3Midlet.RANDOM). The returned generator produces exactly the
     * sequence of java.util.Random for the same seed, but its state can be saved/restored (see {@link SnapshotRandom}).
     */
    public static Random newGameRandom() {
        if (SEED_PROP != null) return new SnapshotRandom(Long.parseLong(SEED_PROP.trim()));
        if (DETERMINISTIC) return new SnapshotRandom(20080101L);
        return new SnapshotRandom(System.nanoTime() ^ 0x5DEECE66DL ^ System.identityHashCode(new Object()));
    }

    private static final java.util.ArrayList<Runnable> pendingLoaders = new java.util.ArrayList<>();

    /**
     * Starts a background load (map / data loading thread). In deterministic mode the load is queued and run
     * to completion on the game thread right after the current tick's logic ({@link #runPendingLoaders}), so
     * the number of ticks a load takes does not depend on thread scheduling.
     */
    public static void startLoader(Runnable task) {
        if (DETERMINISTIC) pendingLoaders.add(task);
        else new Thread(task).start();
    }

    /** Game thread: runs loads queued by {@link #startLoader} (deterministic mode only). */
    public static void runPendingLoaders() {
        while (!pendingLoaders.isEmpty()) pendingLoaders.remove(0).run();
    }

}
