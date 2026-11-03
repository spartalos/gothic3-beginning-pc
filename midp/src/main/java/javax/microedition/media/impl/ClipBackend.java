package javax.microedition.media.impl;

import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.microedition.media.MediaException;

/** PCM WAV played through a preloaded javax.sound.sampled Clip (low latency). */
final class ClipBackend implements Backend {
    private final byte[] wav;
    private final long durationUs;
    private Clip clip;
    private long pausedUs;
    private int run;
    private Runnable onEnd;

    ClipBackend(byte[] wav) throws MediaException {
        this.wav = wav;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat f = in.getFormat();
            durationUs = (long) (in.getFrameLength() * 1_000_000.0 / f.getFrameRate());
        } catch (Exception e) {
            throw new MediaException("Bad WAV data: " + e);
        }
    }

    @Override
    public synchronized void open() throws MediaException {
        if (clip != null) {
            return;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat f = in.getFormat();
            Clip c = (Clip) AudioSystem.getLine(new DataLine.Info(Clip.class, f));
            c.open(in);
            c.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) {
                    naturalStop(c);
                }
            });
            clip = c;
            MasterVolume.register(this);
            applyVolume();
            c.setMicrosecondPosition(pausedUs);
        } catch (Exception e) {
            throw new MediaException("Cannot open audio clip: " + e);
        }
    }

    /** Applies the global output level (options screen) to the open clip. */
    synchronized void applyVolume() {
        Clip c = clip;
        if (c == null) {
            return;
        }
        try {
            if (c.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                javax.sound.sampled.FloatControl g = (javax.sound.sampled.FloatControl) c.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), MasterVolume.decibels())));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void naturalStop(Clip c) {
        Runnable r;
        synchronized (this) {
            if (c != clip || onEnd == null) {
                return;
            }
            r = onEnd;
            onEnd = null;
            pausedUs = 0;
            c.setFramePosition(0);
        }
        r.run();
    }

    @Override
    public synchronized void play(int loops, Runnable end) {
        onEnd = end;
        clip.setMicrosecondPosition(pausedUs);
        if (loops == 1) {
            clip.start();
        } else {
            clip.loop(loops < 0 ? Clip.LOOP_CONTINUOUSLY : loops - 1);
        }
    }

    @Override
    public synchronized void pause() {
        onEnd = null;
        if (clip != null) {
            pausedUs = clip.getMicrosecondPosition();
            clip.stop();
        }
    }

    @Override
    public synchronized void release() {
        onEnd = null;
        pausedUs = 0;
        if (clip != null) {
            Clip c = clip;
            clip = null;
            c.stop();
            c.close();
        }
    }

    @Override
    public long durationUs() {
        return durationUs;
    }

    @Override
    public synchronized long positionUs() {
        return clip != null && clip.isRunning() ? clip.getMicrosecondPosition() : pausedUs;
    }

    @Override
    public synchronized void setPositionUs(long us) {
        pausedUs = us;
        if (clip != null && !clip.isRunning()) {
            clip.setMicrosecondPosition(us);
        }
    }
}
