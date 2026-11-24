package javax.microedition.media.impl;

import java.io.File;
import javax.microedition.media.MediaException;
import javax.sound.midi.Sequence;

/**
 * MIDI music played from a pre-rendered PCM buffer (see {@link MidiRenderer}) through a line with a generous buffer, fed by a
 * high-priority thread that does nothing but copy bytes. Nothing is synthesised in real time, so loading hitches elsewhere
 * (GC, CPU spikes) cannot starve the audio. Until the render of this track is ready (first run: a few seconds in the background,
 * later runs: the disk cache) every call goes to the live {@link MidiBackend}; the choice is made at open/play time.
 *
 * Loops follow the live behaviour (the whole sequence, tick 0 to the last tick): the sequence's note-release/reverb tail is
 * mixed over the start of the next pass and played out after the last one. The loop count, media time, endOfMedia and the
 * master volume (applied while copying, live) mean the same as in the live backend.
 */
final class PcmMidiBackend implements Backend {
    /** Line buffer; the feeder may be starved this long before the music breaks up. */
    static final int BUFFER_MS = Integer.getInteger("g3.audio.bufferMs", 400);
    private static final int CHUNK = 2048; // frames per write (46 ms)

    private final MidiBackend live;
    private final long durationUs;
    private final String key;

    private MidiRenderer.Pcm pcm;     // non-null = PCM mode
    private AudioSink sink;
    private volatile long pausedUs;
    private Run run;

    private static final class Run {
        volatile boolean stop;
        volatile boolean done;       // everything incl. tail has been handed to the line
        volatile boolean bodyDone;   // the music itself has been played out
        long base;                   // line frame position at start
        int startOff;                // first pass starts at this frame
        long totalBody = -1;         // frames of music until the end (-1 = loops forever)
        Thread thread;
    }

    PcmMidiBackend(MidiBackend live, Sequence seq, byte[] midi, File sf) {
        this.live = live;
        this.durationUs = seq.getMicrosecondLength();
        this.key = MidiRenderer.key(midi, sf);
        if (MidiRenderer.available()) {
            MidiRenderer.request(key, seq, sf);
        }
    }

    /** Switches to PCM mode when the render is ready and a line can be opened. */
    private boolean tryPcm() {
        if (pcm != null) {
            return true;
        }
        if (!MidiRenderer.available()) {
            return false;
        }
        MidiRenderer.Pcm p = MidiRenderer.get(key);
        if (p == null) {
            return false;
        }
        try {
            sink = AudioSink.open(MidiRenderer.RATE, BUFFER_MS);
        } catch (Throwable t) {
            System.err.println("[audio] cannot open the PCM line (" + t + "), using the live synthesizer");
            MidiRenderer.disable();
            return false;
        }
        pausedUs = live.positionUs();
        live.release();
        pcm = p;
        if (MidiRenderer.DEBUG) {
            System.err.println("[audio] MIDI " + key.substring(0, 8) + " plays from pre-rendered PCM (" + BUFFER_MS + " ms buffer)");
        }
        return true;
    }

    @Override
    public synchronized void open() throws MediaException {
        if (!tryPcm()) {
            live.open();
        }
    }

    @Override
    public synchronized void play(int loops, Runnable onEnd) {
        if (pcm == null && tryPcm()) {
            // render finished since open(): the live sequencer was released above
        }
        if (pcm == null) {
            live.play(loops, onEnd);
            return;
        }
        stopRun(false);
        final MidiRenderer.Pcm p = pcm;
        final AudioSink s = sink;
        final Run r = new Run();
        int off = (int) Math.round(pausedUs * (double) MidiRenderer.RATE / 1_000_000.0);
        r.startOff = off >= 0 && off < p.bodyFrames ? off : 0;
        if (loops > 0) {
            r.totalBody = (long) (p.bodyFrames - r.startOff) + (long) (loops - 1) * p.bodyFrames;
        }
        s.flush();
        r.base = s.framePosition();
        s.start();
        r.thread = new Thread(() -> feed(r, p, s, loops, onEnd), "media-midi-pcm");
        r.thread.setDaemon(true);
        r.thread.setPriority(Thread.MAX_PRIORITY);
        run = r;
        r.thread.start();
    }

    private void feed(Run r, MidiRenderer.Pcm p, AudioSink s, int loops, Runnable onEnd) {
        final int body = p.bodyFrames;
        final byte[] out = new byte[CHUNK * MidiRenderer.FRAME];
        int pos = r.startOff;
        long iter = 0;
        long written = 0; // body frames handed to the line
        boolean ended = false;
        while (!r.stop) {
            boolean last = loops > 0 && iter == loops - 1;
            while (pos < body && !r.stop) {
                int n = Math.min(CHUNK, body - pos);
                fill(p, pos, n, iter > 0, out);
                s.write(out, 0, n * MidiRenderer.FRAME);
                pos += n;
                written += n;
            }
            if (r.stop) {
                return;
            }
            if (last) {
                break;
            }
            iter++;
            pos = 0;
        }
        // the tail rings out after the last pass; the music counts as ended when its last frame has been played
        int t = 0;
        while (t < MidiRenderer.TAIL && !r.stop) {
            int n = Math.min(CHUNK, MidiRenderer.TAIL - t);
            fillTail(p, t, n, out);
            s.write(out, 0, n * MidiRenderer.FRAME);
            t += n;
            if (!ended && s.framePosition() - r.base >= written) {
                ended = true;
                end(r, onEnd);
            }
        }
        while (!r.stop && !ended) {
            if (s.framePosition() - r.base >= written) {
                ended = true;
                end(r, onEnd);
            } else {
                sleep(5);
            }
        }
        r.done = true;
        long total = written + MidiRenderer.TAIL;
        for (int i = 0; i < 600 && !r.stop && s.framePosition() - r.base < total; i++) {
            sleep(5);
        }
        if (!r.stop) {
            s.stop();
            s.flush();
        }
    }

    private void end(Run r, Runnable onEnd) {
        r.bodyDone = true;
        if (r.stop) {
            return;
        }
        pausedUs = 0;
        onEnd.run();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Copies {@code n} frames of the body at {@code pos}, mixing in the previous pass's tail at the start of repeats. */
    private static void fill(MidiRenderer.Pcm p, int pos, int n, boolean mixTail, byte[] out) {
        final byte[] d = p.data;
        final float vol = MasterVolume.get();
        final int mixFrames = mixTail ? Math.max(0, Math.min(n, MidiRenderer.TAIL - pos)) : 0;
        if (vol >= 0.9999f && mixFrames == 0) {
            System.arraycopy(d, pos * MidiRenderer.FRAME, out, 0, n * MidiRenderer.FRAME);
            return;
        }
        int src = pos * MidiRenderer.FRAME;
        int tail = (p.bodyFrames + pos) * MidiRenderer.FRAME;
        for (int i = 0; i < n * 2; i++) {
            int s = (short) ((d[src] & 0xFF) | (d[src + 1] << 8));
            if (i < mixFrames * 2) {
                s += (short) ((d[tail] & 0xFF) | (d[tail + 1] << 8));
            }
            put(out, i * 2, s, vol);
            src += 2;
            tail += 2;
        }
    }

    private static void fillTail(MidiRenderer.Pcm p, int t, int n, byte[] out) {
        final byte[] d = p.data;
        final float vol = MasterVolume.get();
        int src = (p.bodyFrames + t) * MidiRenderer.FRAME;
        if (vol >= 0.9999f) {
            System.arraycopy(d, src, out, 0, n * MidiRenderer.FRAME);
            return;
        }
        for (int i = 0; i < n * 2; i++) {
            put(out, i * 2, (short) ((d[src] & 0xFF) | (d[src + 1] << 8)), vol);
            src += 2;
        }
    }

    private static void put(byte[] out, int at, int s, float vol) {
        int v = Math.round(s * vol);
        v = v > 32767 ? 32767 : Math.max(v, -32768);
        out[at] = (byte) v;
        out[at + 1] = (byte) (v >> 8);
    }

    /** Stops the feeder, drops queued audio and (optionally) fades out the 30 ms after the current position instead of cutting. */
    private void stopRun(boolean fade) {
        Run r = run;
        if (r == null) {
            return;
        }
        run = null;
        final boolean playing = !r.done;
        long posFrames = playedFrames(r);
        r.stop = true;
        sink.flush();
        try {
            r.thread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sink.flush();
        if (fade && playing) {
            int n = MidiRenderer.RATE * 30 / 1000;
            int from = (int) (posFrames % pcm.bodyFrames);
            if (from + n > pcm.data.length / MidiRenderer.FRAME) {
                n = Math.max(0, pcm.data.length / MidiRenderer.FRAME - from);
            }
            byte[] out = new byte[n * MidiRenderer.FRAME];
            float vol = MasterVolume.get();
            for (int i = 0; i < n; i++) {
                float g = vol * (1f - (i + 1f) / n);
                for (int c = 0; c < 2; c++) {
                    int o = (from + i) * MidiRenderer.FRAME + c * 2;
                    int s = (short) ((pcm.data[o] & 0xFF) | (pcm.data[o + 1] << 8));
                    put(out, (i * 2 + c) * 2, s, g);
                }
            }
            sink.start();
            sink.write(out, 0, out.length);
            sleep(32);
        }
        sink.stop();
        sink.flush();
        if (MidiRenderer.DEBUG) {
            System.err.println("[audio] PCM music stopped, underruns so far on this line: " + sink.underruns());
        }
    }

    /** Frames of the sequence position (within the pass) that the line has played in this run. */
    private long playedFrames(Run r) {
        long played = Math.max(0, sink.framePosition() - r.base);
        int body = pcm.bodyFrames;
        long r0 = body - r.startOff;
        return played < r0 ? r.startOff + played : (played - r0) % body;
    }

    @Override
    public synchronized void pause() {
        if (pcm == null) {
            live.pause();
            return;
        }
        Run r = run;
        if (r != null) {
            pausedUs = r.done || r.bodyDone ? 0 : Math.min(durationUs, playedFrames(r) * 1_000_000L / MidiRenderer.RATE);
            stopRun(true);
        }
    }

    @Override
    public synchronized void release() {
        if (pcm == null) {
            live.release();
            return;
        }
        stopRun(true);
        pausedUs = 0;
        sink.close();
        sink = null;
        pcm = null;
        live.release();
    }

    @Override
    public long durationUs() {
        return durationUs;
    }

    @Override
    public synchronized long positionUs() {
        if (pcm == null) {
            return live.positionUs();
        }
        Run r = run;
        if (r == null || r.done || r.bodyDone) {
            return pausedUs;
        }
        return Math.min(durationUs, playedFrames(r) * 1_000_000L / MidiRenderer.RATE);
    }

    @Override
    public synchronized void setPositionUs(long us) {
        pausedUs = us;
        live.setPositionUs(us);
    }
}
