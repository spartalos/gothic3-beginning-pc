package javax.microedition.midlet;

import gothic3.platform.Platform;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * NON-MIDP helper (lives in the midlet package to reach the protected lifecycle methods).
 * Loads a MIDlet class by name, fills application properties from the jar manifest of
 * the class's code source, constructs it and calls startApp().
 */
public final class MIDletLauncher {
    private MIDletLauncher() {}

    private static MIDlet running;

    /** Reads MIDlet-* / MicroEdition-* attributes from the jar that holds the class. */
    public static void loadManifest(Class<?> cls) {
        try {
            URL loc = cls.getProtectionDomain().getCodeSource().getLocation();
            java.io.File f = new java.io.File(loc.toURI());
            if (f.isFile()) {
                try (JarFile jf = new JarFile(f)) {
                    Manifest m = jf.getManifest();
                    if (m != null) {
                        Attributes a = m.getMainAttributes();
                        for (Object k : a.keySet()) {
                            Platform.appProperties().put(k.toString(), a.getValue(k.toString()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Platform.log("could not read manifest: " + e);
        }
    }

    public static void setDefaultSystemProperties() {
        def("microedition.configuration", "CLDC-1.1");
        def("microedition.profiles", "MIDP-2.0");
        def("microedition.platform", "NokiaS40-PC");
        def("microedition.locale", "en-US");
        def("microedition.encoding", "ISO-8859-1");
    }

    private static void def(String k, String v) {
        if (System.getProperty(k) == null) System.setProperty(k, v);
    }

    /** Instantiates and starts the MIDlet on the calling thread (call from the game thread). */
    public static MIDlet launch(String className) throws Exception {
        setDefaultSystemProperties();
        ClassLoader loader = MIDletLauncher.class.getClassLoader();
        String gcp = System.getProperty("g3.game.classpath");
        if (gcp != null && !gcp.isEmpty()) {
            java.util.List<URL> urls = new java.util.ArrayList<>();
            for (String p : gcp.split(java.io.File.pathSeparator)) urls.add(new java.io.File(p).toURI().toURL());
            loader = new gothic3.platform.GameClassLoader(urls.toArray(new URL[0]), loader);
            Platform.setResourceLoader(loader);
        }
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> cls = Class.forName(className, true, loader);
        loadManifest(cls);
        Constructor<?> c = cls.getDeclaredConstructor();
        c.setAccessible(true);
        MIDlet m = (MIDlet) c.newInstance();
        running = m;
        m.startApp();
        return m;
    }

    public static void pause() { if (running != null) running.pauseApp(); }

    public static void destroy() {
        MIDlet m = running;
        if (m == null) return;
        try {
            m.destroyApp(true);
        } catch (MIDletStateChangeException ignored) {
        }
    }
}
