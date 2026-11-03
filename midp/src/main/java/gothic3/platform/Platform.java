package gothic3.platform;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Central, libGDX-free hub of the MIDP shim: logical screen size, published frames,
 * the single "MIDP UI" thread used for key/repaint delivery, application properties
 * and the exit hook.
 *
 * Configuration comes from system properties (g3.width / g3.height / g3.rms.dir) or
 * can be set programmatically before the MIDlet is launched.
 */
public final class Platform {
    static {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    public static final int DEFAULT_WIDTH = 240;
    public static final int DEFAULT_HEIGHT = 320;

    private static volatile int width = Integer.getInteger("g3.width", DEFAULT_WIDTH);
    private static volatile int height = Integer.getInteger("g3.height", DEFAULT_HEIGHT);
    private static final FrameBuffer FRAMES = new FrameBuffer();
    private static final FramePacer PACER = new FramePacer();
    private static final Map<String, String> APP_PROPS = new ConcurrentHashMap<>();
    private static volatile Runnable exitHandler = () -> System.exit(0);
    private static volatile File rmsDir;

    private static final LinkedBlockingQueue<Runnable> UI_QUEUE = new LinkedBlockingQueue<>();
    private static Thread uiThread;

    private static volatile ClassLoader resourceLoader = Platform.class.getClassLoader();
    /** Class loader used for game resources (Image.createImage(String)). */
    public static ClassLoader resourceLoader() { return resourceLoader; }
    public static void setResourceLoader(ClassLoader cl) { resourceLoader = cl; }

    /**
     * Opens a game resource the way a J2ME handset does: fully buffered, so read(byte[],..)
     * returns everything requested, skip() is exact and mark/reset works. Returns null if the
     * resource does not exist. Names are resolved from the classpath root ("/i" and "i" are equal).
     */
    public static java.io.InputStream openResource(String name) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        try (java.io.InputStream in = resourceLoader.getResourceAsStream(n)) {
            return in == null ? null : new java.io.ByteArrayInputStream(in.readAllBytes());
        } catch (java.io.IOException e) {
            log("cannot read resource " + name + ": " + e);
            return null;
        }
    }

    private Platform() {}

    public static int getWidth() { return width; }
    public static int getHeight() { return height; }
    /** Must be called before the MIDlet creates its canvas. */
    public static void setScreenSize(int w, int h) { width = w; height = h; }

    public static FrameBuffer frames() { return FRAMES; }

    /** Frame signalling between the presenter (vsync) and the game thread; see {@link FramePacer}. */
    public static FramePacer pacer() { return PACER; }

    public static Map<String, String> appProperties() { return APP_PROPS; }

    public static void setExitHandler(Runnable r) { exitHandler = r; }
    public static void requestExit() {
        log("MIDlet requested exit");
        exitHandler.run();
    }

    public static File rmsDir() {
        File d = rmsDir;
        if (d == null) {
            String p = System.getProperty("g3.rms.dir");
            if (p != null) d = new File(p);
            else {
                String xdg = System.getenv("XDG_DATA_HOME");
                File base = (xdg != null && !xdg.isEmpty()) ? new File(xdg)
                        : new File(System.getProperty("user.home"), ".local/share");
                d = new File(base, "gothic3-pc/rms");
            }
            rmsDir = d;
        }
        return d;
    }
    public static void setRmsDir(File d) { rmsDir = d; }

    /** Runs r on the MIDP UI thread (key events, repaints) in FIFO order. */
    public static synchronized void postUi(Runnable r) {
        if (uiThread == null) {
            uiThread = new Thread(() -> {
                for (;;) {
                    try {
                        UI_QUEUE.take().run();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }, "MIDP-UI");
            uiThread.setDaemon(true);
            uiThread.start();
        }
        UI_QUEUE.add(r);
    }

    public static boolean onUiThread() { return Thread.currentThread() == uiThread; }

    public static void log(String s) { System.err.println("[midp] " + s); }
}
