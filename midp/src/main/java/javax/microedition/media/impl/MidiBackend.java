package javax.microedition.media.impl;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.microedition.media.MediaException;

/** Standard MIDI file played by a private Sequencer feeding one shared Gervill synthesizer. */
final class MidiBackend implements Backend {
    private static Synthesizer synth;
    private static boolean synthFailed;

    private final Sequence sequence;
    private Sequencer sequencer;
    private Receiver receiver;
    private long pausedUs;
    private int run;

    MidiBackend(Sequence sequence) {
        this.sequence = sequence;
    }

    private static synchronized Synthesizer synth() throws MediaException {
        if (synth == null) {
            if (synthFailed) {
                throw new MediaException("No MIDI synthesizer");
            }
            try {
                Synthesizer s = MidiSystem.getSynthesizer();
                java.io.File sf = SoundbankLocator.find();
                javax.sound.midi.Soundbank bank = sf == null ? null : SoundbankLocator.load(sf);
                if (bank != null && !s.isSoundbankSupported(bank)) {
                    System.err.println("[audio] soundfont " + sf + " is not supported by the synthesizer (using the default bank)");
                    bank = null;
                }
                if (AudioSink.NULL_LINE && MidiRenderer.available()) {
                    s = openNullLive(s.getClass());
                } else {
                    s.open(); // open() always loads the default bank (a load before open is discarded), so swap it afterwards
                if (bank != null) {
                    s.unloadAllInstruments(s.getDefaultSoundbank());
                    s.loadAllInstruments(bank);
                }
                }
                synth = s;
                System.err.println("[audio] MIDI soundbank: " + (bank != null ? sf + " (" + s.getLoadedInstruments().length + " instruments)"
                    : "JDK default (crude; put a General MIDI .sf2 next to the app jar or set audio.soundfont, docs/audio.md)"));
            } catch (Throwable t) {
                synthFailed = true;
                throw new MediaException("Cannot open MIDI synthesizer: " + t);
            }
        }
        return synth;
    }

    /**
     * -Dg3.audio.nullLine=true (debug): the live synthesizer renders in stream mode and a "pump" thread pulls its output in real time
     * into a simulated line (120 ms buffer, 30 ms reads, max priority: Gervill's own defaults) that counts underruns instead of playing.
     */
    private static Synthesizer openNullLive(Class<?> cls) throws Exception {
        Synthesizer s = (Synthesizer) cls.getConstructor().newInstance();
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(MidiRenderer.RATE, 16, 2, true, false);
        javax.sound.sampled.AudioInputStream in = (javax.sound.sampled.AudioInputStream) cls
            .getMethod("openStream", javax.sound.sampled.AudioFormat.class, java.util.Map.class).invoke(s, fmt, null);
        AudioSink.NullSink sink = new AudioSink.NullSink(MidiRenderer.RATE, 120, "live");
        sink.start();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[MidiRenderer.RATE * 30 / 1000 * 4];
            try {
                while (true) {
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n < 0) {
                            return;
                        }
                        off += n;
                    }
                    sink.write(buf, 0, buf.length);
                }
            } catch (Exception e) {
                // closed
            }
        }, "media-midi-live-pump");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(sink::close));
        return s;
    }

    /** Sends the global output level to the shared synthesizer as a universal "master volume" SysEx (14 bit). */
    static void applyMasterVolume() {
        Synthesizer s;
        synchronized (MidiBackend.class) {
            s = synth;
        }
        if (s == null) {
            return;
        }
        try (Receiver r = s.getReceiver()) {
            int v = Math.round(MasterVolume.get() * 16383f);
            byte[] d = {(byte) 0xF0, 0x7F, 0x7F, 0x04, 0x01, (byte) (v & 0x7F), (byte) ((v >> 7) & 0x7F), (byte) 0xF7};
            r.send(new javax.sound.midi.SysexMessage(d, d.length), -1);
        } catch (Exception ignored) {
        }
    }

    @Override
    public synchronized void open() throws MediaException {
        if (sequencer != null) {
            return;
        }
        Sequencer sq = null;
        try {
            Synthesizer s = synth();
            applyMasterVolume();
            sq = MidiSystem.getSequencer(false);
            sq.open();
            sq.setSequence(sequence);
            receiver = s.getReceiver();
            sq.getTransmitter().setReceiver(receiver);
            sq.setMicrosecondPosition(pausedUs);
            sequencer = sq;
        } catch (Throwable t) {
            if (sq != null && sq.isOpen()) {
                sq.close();
            }
            if (receiver != null) {
                receiver.close();
                receiver = null;
            }
            throw new MediaException("Cannot open MIDI sequencer: " + t);
        }
    }

    @Override
    public synchronized void play(int loops, Runnable onEnd) {
        final int id = ++run;
        final Sequencer sq = sequencer;
        sq.setLoopStartPoint(0);
        sq.setLoopEndPoint(-1);
        sq.setLoopCount(loops < 0 ? Sequencer.LOOP_CONTINUOUSLY : loops - 1);
        sq.setMicrosecondPosition(pausedUs);
        sq.start();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(50);
                while (true) {
                    synchronized (MidiBackend.this) {
                        if (id != run || sequencer != sq) {
                            return;
                        }
                        if (!sq.isRunning()) {
                            // Never rewind here: setMicrosecondPosition from a non-play thread while the JDK
                            // sequencer's play thread is finishing deadlocks (TempoCache/DataPump lock inversion).
                            // The rewind is applied by the next play().
                            run++;
                            pausedUs = 0;
                            silence();
                            break;
                        }
                    }
                    Thread.sleep(20);
                }
                onEnd.run();
            } catch (InterruptedException ignored) {
            }
        }, "media-midi-end");
        t.setDaemon(true);
        t.start();
    }

    private void silence() {
        if (receiver == null) {
            return;
        }
        try {
            for (int ch = 0; ch < 16; ch++) {
                receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 64, 0), -1);
                receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0), -1);
                receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 120, 0), -1);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public synchronized void pause() {
        run++;
        if (sequencer != null) {
            pausedUs = sequencer.getMicrosecondPosition();
            sequencer.stop();
            silence();
        }
    }

    @Override
    public synchronized void release() {
        run++;
        pausedUs = 0;
        if (sequencer != null) {
            sequencer.stop();
            silence();
            sequencer.close();
            sequencer = null;
        }
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
    }

    @Override
    public long durationUs() {
        return sequence.getMicrosecondLength();
    }

    @Override
    public synchronized long positionUs() {
        return sequencer != null && sequencer.isRunning() ? sequencer.getMicrosecondPosition() : pausedUs;
    }

    @Override
    public synchronized void setPositionUs(long us) {
        pausedUs = us; // applied by the next play() (see the end-of-track note there)
    }
}
