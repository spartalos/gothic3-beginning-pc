package gothic3.desktop;

import gothic3.platform.Platform;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Periodically writes the latest published logical framebuffer as PNG (automation/debug). */
public final class FrameDumper implements Runnable {
    private final File dir;
    private final long everyMs;
    private final long start = System.currentTimeMillis();
    private volatile boolean running = true;
    private long lastSerial = -1;

    public FrameDumper(File dir, long everyMs) {
        this.dir = dir;
        this.everyMs = everyMs;
        dir.mkdirs();
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(everyMs);
            } catch (InterruptedException e) {
                return;
            }
            long[] info = Platform.frames().info();
            if (info[2] == lastSerial || info[0] == 0) continue;
            lastSerial = info[2];
            write(new File(dir, String.format("frame_%07d.png", System.currentTimeMillis() - start)));
        }
    }

    private java.util.function.Consumer<File> windowShot;

    /** 3D view: the logical frame is only the HUD layer, so script shots save the presented window instead. */
    public void windowShot(java.util.function.Consumer<File> c) { windowShot = c; }

    /** Always saves the presented window (so overlays drawn by the presenter, like the options screen, are included). */
    public void windowShotNamed(String name) {
        File f = new File(dir, "shot_" + name + ".png");
        if (windowShot != null) windowShot.accept(f); else write(f);
    }

    public void shot(String name) {
        File f = new File(dir, "shot_" + name + ".png");
        if (windowShot != null && gothic3.platform.RenderConfig.view3d()) windowShot.accept(f);
        else write(f);
    }

    private void write(File f) {
        long[] info = Platform.frames().info();
        int w = (int) info[0], h = (int) info[1];
        if (w == 0) return;
        int[] px = new int[w * h];
        Platform.frames().copyIfNewer(-1, px);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, px, 0, w);
        try {
            ImageIO.write(bi, "png", f);
        } catch (Exception e) {
            System.err.println("[dump] " + e);
        }
    }
}
