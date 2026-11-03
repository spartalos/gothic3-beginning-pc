package javax.microedition.media.impl;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Process-wide output level (0 = silent, 1 = unchanged) shared by the clip and MIDI backends. */
final class MasterVolume {
    private static volatile float level = 1f;
    private static final Map<ClipBackend, Boolean> CLIPS = Collections.synchronizedMap(new WeakHashMap<>());

    private MasterVolume() {
    }

    static float get() {
        return level;
    }

    static void set(float v) {
        level = Math.max(0f, Math.min(1f, v));
        ClipBackend[] cs;
        synchronized (CLIPS) {
            cs = CLIPS.keySet().toArray(new ClipBackend[0]);
        }
        for (ClipBackend c : cs) {
            c.applyVolume();
        }
        MidiBackend.applyMasterVolume();
    }

    static void register(ClipBackend c) {
        CLIPS.put(c, Boolean.TRUE);
    }

    /** Level as decibels of gain (amplitude scaling), clamped by the caller to the control's range. */
    static float decibels() {
        return level <= 0.0001f ? -80f : (float) (20.0 * Math.log10(level));
    }
}
