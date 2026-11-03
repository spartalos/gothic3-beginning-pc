import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.impl.PlayerFactory;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Manual stress harness (not run by gradle): short SILENT MIDI files (controller events only, no notes, master
 * volume 0) are played to the end over and over while other threads call getState/stop/start/deallocate on the same
 * players, mimicking the game thread. Exit code 2 and a deadlock report if a "game" thread stops making progress.
 *
 *   javac -cp midp/build/classes/java/main -d /tmp/ms midp/src/stress/java/MidiStress.java && java -cp midp/build/classes/java/main:/tmp/ms MidiStress [seconds [midiMs [players]]]
 */
public class MidiStress {
    static byte[] silentMidi(int ms) throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480); // 120 bpm default: 960 ticks per second
        Track t = seq.createTrack();
        t.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 7, 100), 0));
        t.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), ms * 960L / 1000));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        MidiSystem.write(seq, 1, o);
        return o.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        int seconds = a.length > 0 ? Integer.parseInt(a[0]) : 60;
        PlayerFactory.setMasterVolume(0f);
        byte[] midi = silentMidi(a.length > 1 ? Integer.parseInt(a[1]) : 120);
        final int n = a.length > 2 ? Integer.parseInt(a[2]) : 4;
        final Player[] ps = new Player[n];
        for (int i = 0; i < n; i++) {
            ps[i] = PlayerFactory.create(midi, "audio/midi");
            ps[i].addPlayerListener((p, e, d) -> { });
        }
        final AtomicLong progress = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final long end = System.currentTimeMillis() + seconds * 1000L;
        Thread[] ts = new Thread[3];
        for (int k = 0; k < ts.length; k++) {
            final int seed = k;
            ts[k] = new Thread(() -> {
                Random r = new Random(seed);
                while (System.currentTimeMillis() < end) {
                    Player p = ps[r.nextInt(n)];
                    try {
                        switch (r.nextInt(6)) {
                            case 0: case 1: p.setLoopCount(1); p.start(); break;
                            case 2: p.getState(); p.getMediaTime(); break;
                            case 3: p.stop(); break;
                            case 4: p.deallocate(); break;
                            default: p.setMediaTime(0); p.getState(); break;
                        }
                    } catch (IllegalStateException | javax.microedition.media.MediaException e) {
                        // legal state errors
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    }
                    progress.incrementAndGet();
                    try { Thread.sleep(r.nextInt(8)); } catch (InterruptedException e) { return; }
                }
            }, "fake-game-" + k);
            ts[k].setDaemon(true);
            ts[k].start();
        }
        long last = -1, lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500);
            long p = progress.get();
            if (p != last) { last = p; lastChange = System.currentTimeMillis(); }
            else if (System.currentTimeMillis() - lastChange > 10000) {
                System.out.println("HANG: no progress for 10 s, ops=" + p);
                var mx = ManagementFactory.getThreadMXBean();
                long[] dl = mx.findDeadlockedThreads();
                if (dl != null) for (ThreadInfo ti : mx.getThreadInfo(dl, true, true)) System.out.println(ti);
                System.exit(2);
            }
        }
        System.out.println("OK ops=" + progress.get() + " errors=" + errors.get());
        System.exit(errors.get() == 0 ? 0 : 1);
    }
}
