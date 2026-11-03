package javax.microedition.media.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Properties;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;

/**
 * Creates players from raw media bytes. AMR (undecodable by the JDK) is mapped, via the SHA-1 of the
 * original bytes, to a build-time converted WAV listed in /audio/index.properties.
 */
public final class PlayerFactory {
    private static Properties amrIndex;

    private PlayerFactory() {
    }

    static final boolean DEBUG = Boolean.getBoolean("g3.media.debug");
    /** --no-audio: every player keeps its timing and events but produces no sound. */
    static final boolean MUTED = Boolean.getBoolean("g3.audio.off");

    /**
     * Global output level 0..1 (in-game options screen). Applied to every open WAV/AMR clip (master gain) and to the
     * shared MIDI synthesizer (master volume SysEx); players opened later pick it up when they open.
     */
    public static void setMasterVolume(float level) {
        MasterVolume.set(level);
    }

    public static float masterVolume() {
        return MasterVolume.get();
    }

    private static Backend audible(Backend b, long durationUs) {
        return MUTED ? new SilentBackend(durationUs) : new AsyncBackend(new FallbackBackend(b, durationUs));
    }

    public static Player create(byte[] data, String type) throws MediaException {
        if (DEBUG) {
            System.err.println("[media] createPlayer type=" + type + " bytes=" + data.length);
        }
        if (startsWith(data, "MThd")) {
            return midi(data);
        }
        if (startsWith(data, "#!AMR")) {
            return amr(data);
        }
        if (startsWith(data, "RIFF")) {
            return new BasePlayer(audible(new ClipBackend(data), wavDuration(data)), "audio/x-wav");
        }
        throw new MediaException("Unsupported media type: " + type);
    }

    private static Player midi(byte[] data) throws MediaException {
        try {
            Sequence seq = MidiSystem.getSequence(new ByteArrayInputStream(data));
            return new BasePlayer(MUTED ? new SilentBackend(seq.getMicrosecondLength()) : new AsyncBackend(new FallbackBackend(new MidiBackend(seq), seq.getMicrosecondLength())), "audio/midi");
        } catch (Exception e) {
            throw new MediaException("Invalid MIDI data: " + e);
        }
    }

    private static Player amr(byte[] data) throws MediaException {
        String hash = sha1(data);
        String path = index().getProperty(hash);
        if (path != null) {
            try (InputStream in = gothic3.platform.Platform.resourceLoader().getResourceAsStream(path)) {
                if (in != null) {
                    byte[] wav = in.readAllBytes();
                    return new BasePlayer(audible(new ClipBackend(wav), wavDuration(wav)), "audio/amr");
                }
            } catch (IOException e) {
                System.err.println("[media] cannot read " + path + ": " + e);
            }
        }
        System.err.println("[media] WARNING: unknown AMR data (sha1 " + hash + "), playing silence");
        return new BasePlayer(new SilentBackend(amrDurationUs(data)), "audio/amr");
    }

    private static synchronized Properties index() {
        if (amrIndex == null) {
            Properties p = new Properties();
            try (InputStream in = gothic3.platform.Platform.resourceLoader().getResourceAsStream("audio/index.properties")) {
                if (in != null) {
                    p.load(in);
                } else {
                    System.err.println("[media] WARNING: /audio/index.properties missing");
                }
            } catch (IOException e) {
                System.err.println("[media] cannot read AMR index: " + e);
            }
            amrIndex = p;
        }
        return amrIndex;
    }

    private static long wavDuration(byte[] wav) {
        try (javax.sound.sampled.AudioInputStream in =
                javax.sound.sampled.AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return (long) (in.getFrameLength() * 1_000_000.0 / in.getFormat().getFrameRate());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Duration of AMR-NB data by walking the frame headers (20 ms per frame). */
    static long amrDurationUs(byte[] d) {
        final int[] size = {13, 14, 16, 18, 20, 21, 27, 32, 6, 6, 6, 6, 1, 1, 1, 1};
        int pos = 6;
        long frames = 0;
        while (pos < d.length) {
            int ft = (d[pos] >> 3) & 0x0F;
            pos += size[ft];
            frames++;
        }
        return frames * 20_000L;
    }

    private static boolean startsWith(byte[] d, String s) {
        if (d.length < s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (d[i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public static String sha1(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(d);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
