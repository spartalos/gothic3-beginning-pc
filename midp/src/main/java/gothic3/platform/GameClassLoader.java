package gothic3.platform;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loads the game classes (kept OFF the application classpath) and serves resources as fully
 * buffered in-memory streams. J2ME resource streams support mark/reset, return full reads
 * and skip exactly as requested; JDK jar streams do not, and the original game relies on
 * all three (sequential scan of the "/i" archive with skip/read).
 * Parent-first delegation, so javax.microedition.* always comes from the shim.
 */
public final class GameClassLoader extends URLClassLoader {
    public GameClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream in = super.getResourceAsStream(name);
        if (in == null) return null;
        try (InputStream src = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(32, src.available()));
            byte[] buf = new byte[16384];
            int r;
            while ((r = src.read(buf)) > 0) bo.write(buf, 0, r);
            return new ByteArrayInputStream(bo.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }
}
