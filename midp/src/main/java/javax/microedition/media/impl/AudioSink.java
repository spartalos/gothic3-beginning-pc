package javax.microedition.media.impl;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Output of the pre-rendered MIDI music: a real {@link SourceDataLine}, or with {@code -Dg3.audio.nullLine=true} a silent real-time simulation. */
interface AudioSink {
    /** -Dg3.audio.nullLine=true: no sound device, a timing-only simulated line that counts underruns (debug / measurements). */
    boolean NULL_LINE = Boolean.getBoolean("g3.audio.nullLine");

    void start();

    /** Stops consuming; data already written stays queued. */
    void stop();

    /** Discards queued data. */
    void flush();

    /** Blocks while the buffer is full. */
    void write(byte[] b, int off, int len);

    /** Frames the device has consumed so far (monotonic, survives flush). */
    long framePosition();

    int bufferFrames();

    /** Number of times the device ran dry while running. */
    int underruns();

    void close();

    static AudioSink open(int rate, int bufferMs) throws LineUnavailableException {
        if (NULL_LINE) {
            return new NullSink(rate, bufferMs, "pcm");
        }
        AudioFormat fmt = new AudioFormat(rate, 16, 2, true, false);
        int bytes = rate * bufferMs / 1000 * 4;
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt, bytes));
        line.open(fmt, bytes);
        return new JavaSink(line);
    }

    final class JavaSink implements AudioSink {
        private final SourceDataLine line;
        private int underruns;
        private boolean wasEmpty;

        JavaSink(SourceDataLine line) {
            this.line = line;
        }

        @Override public void start() {
            line.start();
        }

        @Override public void stop() {
            line.stop();
        }

        @Override public void flush() {
            line.flush();
            wasEmpty = false;
        }

        @Override public void write(byte[] b, int off, int len) {
            // empty buffer while running = the device has starved since the last write
            boolean empty = line.isRunning() && line.available() >= line.getBufferSize();
            if (empty && !wasEmpty) {
                underruns++;
            }
            wasEmpty = empty;
            line.write(b, off, len);
        }

        @Override public long framePosition() {
            return line.getLongFramePosition();
        }

        @Override public int bufferFrames() {
            return line.getBufferSize() / 4;
        }

        @Override public int underruns() {
            return underruns;
        }

        @Override public void close() {
            line.close();
        }
    }

    /** Simulates a device that consumes in real time and blocks writers when full; counts starvation like a real line would. */
    final class NullSink implements AudioSink {
        private final int rate;
        private final long capNs;
        private final String name;
        private long writtenFrames;   // incl. queued
        private long endNs;           // when everything queued will have been played
        private boolean running;
        private long stoppedRemainNs;
        private int underruns;
        private long worstGapNs;
        private long minAheadNs = Long.MAX_VALUE;
        private boolean primed;       // something written since start/flush: only then can the device run dry

        NullSink(int rate, int bufferMs, String name) {
            this.rate = rate;
            this.capNs = bufferMs * 1_000_000L;
            this.name = name;
            this.endNs = System.nanoTime();
        }

        private long ns(long frames) {
            return frames * 1_000_000_000L / rate;
        }

        @Override public synchronized void start() {
            if (!running) {
                running = true;
                endNs = System.nanoTime() + stoppedRemainNs;
                stoppedRemainNs = 0;
            }
        }

        @Override public synchronized void stop() {
            if (running) {
                running = false;
                stoppedRemainNs = Math.max(0, endNs - System.nanoTime());
            }
        }

        @Override public synchronized void flush() {
            long q = queuedNs();
            writtenFrames -= q * rate / 1_000_000_000L;
            endNs = System.nanoTime();
            stoppedRemainNs = 0;
            primed = false;
        }

        private long queuedNs() {
            return running ? Math.max(0, endNs - System.nanoTime()) : stoppedRemainNs;
        }

        @Override public void write(byte[] b, int off, int len) {
            long addNs = ns(len / 4);
            while (true) {
                synchronized (this) {
                    long q = queuedNs();
                    if (!running || q + addNs <= capNs) {
                        long now = System.nanoTime();
                        if (running) {
                            if (!primed) {
                                endNs = now;
                            } else if (endNs < now) { // ran dry
                                underruns++;
                                worstGapNs = Math.max(worstGapNs, now - endNs);
                                endNs = now;
                            } else {
                                minAheadNs = Math.min(minAheadNs, endNs - now);
                            }
                            primed = true;
                            endNs += addNs;
                        } else {
                            stoppedRemainNs += addNs;
                        }
                        writtenFrames += len / 4;
                        return;
                    }
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override public synchronized long framePosition() {
            return writtenFrames - queuedNs() * rate / 1_000_000_000L;
        }

        @Override public int bufferFrames() {
            return (int) (capNs * rate / 1_000_000_000L);
        }

        @Override public synchronized int underruns() {
            return underruns;
        }

        @Override public synchronized void close() {
            System.err.println("[audio] nullLine(" + name + ") closed: underruns=" + underruns + " worstGapMs=" + worstGapNs / 1_000_000
                + " minAheadMs=" + (minAheadNs == Long.MAX_VALUE ? -1 : minAheadNs / 1_000_000));
        }
    }
}
