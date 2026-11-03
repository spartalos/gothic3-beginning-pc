package gothic3.port;

import java.util.Random;

/**
 * java.util.Random with the identical 48-bit LCG, but with readable / writable state. The renderer's
 * extra in-between frames draw from the shared game RNG exactly like the canonical frame did
 * (state is rewound before and restored after), so the sequence seen by game logic is unchanged.
 */
public final class SnapshotRandom extends Random {
    private static final long serialVersionUID = 1L;
    private static final long MULT = 0x5DEECE66DL, ADD = 0xBL, MASK = (1L << 48) - 1;
    private long s;

    public SnapshotRandom(long seed) {
        super(0);
        setSeed(seed);
    }

    @Override
    public synchronized void setSeed(long seed) {
        s = (seed ^ MULT) & MASK;
    }

    @Override
    protected synchronized int next(int bits) {
        s = (s * MULT + ADD) & MASK;
        return (int) (s >>> (48 - bits));
    }

    public synchronized long state() { return s; }

    public synchronized void state(long v) { s = v & MASK; }
}
