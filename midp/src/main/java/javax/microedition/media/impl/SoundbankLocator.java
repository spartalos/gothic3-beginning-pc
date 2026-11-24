package javax.microedition.media.impl;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;

/**
 * Finds the SoundFont for the MIDI music. Order: {@code -Dg3.soundfont=<file or dir>} (the launcher fills it from the config key
 * {@code audio.soundfont}), then {@code soundfont.sf2}, {@code soundfont/*.sf2} or any {@code *.sf2} next to the app jar (the app images
 * ship GeneralUser GS there), then the JDK's default bank (Gervill's generated emergency bank, rather crude).
 */
final class SoundbankLocator {
    private SoundbankLocator() {
    }

    /** The soundfont file to use, or null for the default bank. */
    static File find() {
        String prop = System.getProperty("g3.soundfont");
        if (prop != null && !prop.isBlank()) {
            File f = new File(prop.trim());
            File sf = f.isDirectory() ? firstIn(f) : f;
            if (sf != null && sf.isFile()) {
                return sf;
            }
            System.err.println("[audio] -Dg3.soundfont=" + prop + " not found, looking next to the app");
        }
        File dir = appDir();
        if (dir == null) {
            return null;
        }
        List<File> cands = new ArrayList<>();
        cands.add(new File(dir, "soundfont.sf2"));
        cands.add(firstIn(new File(dir, "soundfont")));
        cands.add(firstIn(dir));
        for (File c : cands) {
            if (c != null && c.isFile()) {
                return c;
            }
        }
        return null;
    }

    /** Loads the bank; null (with a log line) when it cannot be read. */
    static Soundbank load(File f) {
        try {
            return MidiSystem.getSoundbank(f);
        } catch (Throwable t) {
            System.err.println("[audio] cannot load soundfont " + f + ": " + t + " (using the default bank)");
            return null;
        }
    }

    private static File firstIn(File dir) {
        File[] l = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".sf2"));
        if (l == null || l.length == 0) {
            return null;
        }
        Arrays.sort(l);
        return l[0];
    }

    private static File appDir() {
        try {
            File loc = new File(SoundbankLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return loc.isFile() ? loc.getParentFile() : null; // not for build-output class directories
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
