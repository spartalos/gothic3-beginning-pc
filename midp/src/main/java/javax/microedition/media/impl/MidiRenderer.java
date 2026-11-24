package javax.microedition.media.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/**
 * Renders a MIDI sequence to PCM offline (Gervill in stream mode, no audio device), so the music can be played back from
 * memory by a high-priority feeder thread instead of being synthesised in real time (a real-time synth starves, and the
 * music stutters, whenever the machine is busy loading). Needs {@code com.sun.media.sound.SoftSynthesizer}, which is not
 * exported by java.desktop: the launchers pass {@code --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED} (fat jar:
 * manifest {@code Add-Exports}); without it {@link #available()} is false and the live path is used. Results are cached in
 * memory (soft) and on disk (keyed by MIDI bytes + SoundFont identity + sample rate).
 */
final class MidiRenderer {
    static final int RATE = 44100;
    static final int FRAME = 4; // 16 bit stereo
    /** Frames rendered after the end of the sequence (note releases, reverb) - mixed over the start when looping. */
    static final int TAIL = RATE * 2;
    private static final int VERSION = 1;
    static final boolean DEBUG = Boolean.getBoolean("g3.media.debug");

    /** A rendered track: little-endian 16 bit stereo, {@code body} frames of music followed by {@code TAIL} frames. */
    static final class Pcm {
        final byte[] data;
        final int bodyFrames;

        Pcm(byte[] data, int bodyFrames) {
            this.data = data;
            this.bodyFrames = bodyFrames;
        }
    }

    private static final Map<String, SoftReference<Pcm>> MEM = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final List<Runnable> QUEUE = new ArrayList<>();
    private static Thread worker;
    private static Boolean avail;
    private static Soundbank bankCache;

    private MidiRenderer() {
    }

    static synchronized void disable() {
        avail = false;
    }

    static synchronized boolean available() {
        if (avail == null) {
            boolean ok = false;
            try {
                Class<?> c = Class.forName("com.sun.media.sound.SoftSynthesizer");
                Object o = c.getConstructor().newInstance();
                c.getMethod("openStream", AudioFormat.class, Map.class);
                ok = o instanceof Synthesizer;
            } catch (Throwable t) {
                System.err.println("[audio] offline MIDI rendering unavailable (" + t + "); using the live synthesizer."
                    + " Add --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED to the JVM options.");
            }
            avail = ok;
        }
        return avail;
    }

    static String key(byte[] midi, File sf) {
        String id = sf == null ? "default" : sf.getName() + ":" + sf.length() + ":" + sf.lastModified();
        return PlayerFactory.sha1((PlayerFactory.sha1(midi) + "|" + id + "|" + RATE + "|v" + VERSION).getBytes());
    }

    private static File cacheDir() {
        String p = System.getProperty("g3.audio.cache");
        if (p != null && !p.isBlank()) {
            return new File(p);
        }
        File rms = gothic3.platform.Platform.rmsDir();
        // the normal layout is <data>/gothic3-pc/rms; a custom / temporary rms dir (deterministic runs) keeps its cache inside itself
        File base = rms.getName().equals("rms") ? rms.getParentFile() : null;
        return base == null ? new File(rms, "music-cache") : new File(base, "cache/music");
    }

    /** The rendered track if it is ready (memory, else disk cache), else null; never renders. */
    static synchronized Pcm get(String key) {
        SoftReference<Pcm> r = MEM.get(key);
        Pcm p = r == null ? null : r.get();
        if (p != null) {
            return p;
        }
        File f = new File(cacheDir(), key + ".pcm");
        if (f.isFile()) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16))) {
                if (in.readInt() == 0x4D504331 /* MPC1 */) {
                    int body = in.readInt();
                    byte[] d = new byte[(body + TAIL) * FRAME];
                    in.readFully(d);
                    p = new Pcm(d, body);
                    MEM.put(key, new SoftReference<>(p));
                    return p;
                }
            } catch (IOException e) {
                System.err.println("[audio] unreadable MIDI cache " + f + ": " + e);
                f.delete();
            }
        }
        return null;
    }

    /** Queues a background render (one low-priority thread, one track at a time) unless the track is cached or pending. */
    static synchronized void request(String key, Sequence seq, File sf) {
        if (PENDING.contains(key) || get(key) != null) {
            return;
        }
        PENDING.add(key);
        QUEUE.add(() -> {
            try {
                long t0 = System.nanoTime();
                Pcm p = render(seq, sf);
                store(key, p);
                if (DEBUG) {
                    System.err.println("[audio] rendered MIDI " + key.substring(0, 8) + " " + (p.bodyFrames / (double) RATE) + " s in "
                        + (System.nanoTime() - t0) / 1_000_000 + " ms");
                }
            } catch (Throwable t) {
                System.err.println("[audio] MIDI render failed (live synthesizer stays in use): " + t);
            } finally {
                synchronized (MidiRenderer.class) {
                    PENDING.remove(key);
                }
            }
        });
        if (worker == null) {
            worker = new Thread(MidiRenderer::work, "media-midi-render");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        MidiRenderer.class.notifyAll();
    }

    private static void work() {
        while (true) {
            Runnable r;
            synchronized (MidiRenderer.class) {
                while (QUEUE.isEmpty()) {
                    try {
                        MidiRenderer.class.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                r = QUEUE.remove(0);
            }
            r.run();
            synchronized (MidiRenderer.class) {
                if (QUEUE.isEmpty()) {
                    bankCache = null; // let the 32 MB bank go once the batch is done
                }
            }
        }
    }

    private static synchronized void store(String key, Pcm p) {
        MEM.put(key, new SoftReference<>(p));
        File dir = cacheDir();
        try {
            dir.mkdirs();
            File tmp = new File(dir, key + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16))) {
                out.writeInt(0x4D504331);
                out.writeInt(p.bodyFrames);
                out.write(p.data);
            }
            Files.move(tmp.toPath(), new File(dir, key + ".pcm").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[audio] cannot cache rendered MIDI in " + dir + ": " + e);
        }
    }

    private static synchronized Soundbank bank(File sf) {
        if (sf == null) {
            return null;
        }
        if (bankCache == null) {
            bankCache = SoundbankLocator.load(sf);
        }
        return bankCache;
    }

    /** Synchronous offline render (also used by tests). */
    static Pcm render(Sequence seq, File sf) throws Exception {
        Class<?> c = Class.forName("com.sun.media.sound.SoftSynthesizer");
        Synthesizer syn = (Synthesizer) c.getConstructor().newInstance();
        Method open = c.getMethod("openStream", AudioFormat.class, Map.class);
        AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
        AudioInputStream in = (AudioInputStream) open.invoke(syn, fmt, null);
        try {
            Soundbank b = bank(sf);
            if (b != null && syn.isSoundbankSupported(b)) {
                syn.unloadAllInstruments(syn.getDefaultSoundbank());
                syn.loadAllInstruments(b);
            }
            Receiver rc = syn.getReceiver();
            long lenUs = send(seq, rc);
            int body = (int) Math.round(lenUs * (double) RATE / 1_000_000.0);
            byte[] d = new byte[(body + TAIL) * FRAME];
            int off = 0;
            while (off < d.length) {
                int n = in.read(d, off, Math.min(1 << 16, d.length - off));
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return new Pcm(d, body);
        } finally {
            in.close();
            syn.close();
        }
    }

    /** Sends every event with its timestamp in microseconds (tempo map applied); returns the length in microseconds. */
    private static long send(Sequence seq, Receiver rc) throws Exception {
        Track[] tracks = seq.getTracks();
        List<MidiEvent> all = new ArrayList<>();
        List<long[]> tempos = new ArrayList<>(); // {tick, usPerQuarter}
        for (Track t : tracks) {
            for (int i = 0; i < t.size(); i++) {
                MidiEvent e = t.get(i);
                MidiMessage m = e.getMessage();
                if (m instanceof MetaMessage mm) {
                    if (mm.getType() == 0x51 && mm.getData().length == 3) {
                        byte[] d = mm.getData();
                        tempos.add(new long[] {e.getTick(), ((d[0] & 0xFFL) << 16) | ((d[1] & 0xFFL) << 8) | (d[2] & 0xFFL)});
                    }
                    continue;
                }
                all.add(e);
            }
        }
        tempos.sort((a, b) -> Long.compare(a[0], b[0]));
        all.sort((a, b) -> Long.compare(a.getTick(), b.getTick())); // stable: track order kept for equal ticks
        boolean ppq = seq.getDivisionType() == Sequence.PPQ;
        double res = seq.getResolution();
        double fps = seq.getDivisionType();
        for (MidiEvent e : all) {
            rc.send(e.getMessage(), (long) toUs(e.getTick(), tempos, ppq, res, fps));
        }
        return (long) toUs(seq.getTickLength(), tempos, ppq, res, fps);
    }

    private static double toUs(long tick, List<long[]> tempos, boolean ppq, double res, double fps) {
        if (!ppq) {
            return tick * 1_000_000.0 / (fps * res);
        }
        double us = 0;
        long lastTick = 0;
        double uspq = 500_000;
        for (long[] t : tempos) {
            if (t[0] >= tick) {
                break;
            }
            us += (t[0] - lastTick) * uspq / res;
            lastTick = t[0];
            uspq = t[1];
        }
        return us + (tick - lastTick) * uspq / res;
    }
}
