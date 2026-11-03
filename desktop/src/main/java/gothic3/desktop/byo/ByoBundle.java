package gothic3.desktop.byo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The build inputs shipped inside the app as one resource, {@code byo.zip}, laid out like the repository root:
 * game-patches/ (build.properties, decompile.sha256, g3/*.patch), docs/mapping/, tools/ (tiny-remapper, Vineflower) and
 * game/src/port/java/ (our PortBridge). It contains no game code or game data of the original, only our edits and the tools.
 */
final class ByoBundle {
    private final byte[] zip;

    ByoBundle() throws IOException {
        try (InputStream in = ByoBundle.class.getResourceAsStream("/byo.zip")) {
            if (in == null) throw new IOException("this build of the app has no byo.zip (the game cannot be built at run time)");
            zip = in.readAllBytes();
        }
    }

    /** Identity of the patch set + tools + mapping + app version; part of the cache key, so a new patch set rebuilds the game. */
    String hash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(zip);
            md.update(("\0app=" + appVersion()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String appVersion() {
        String v = ByoBundle.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** game-patches/build.properties (expected jar name / hash / size, known wrong builds). */
    Properties buildProperties() throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.getName().equals("game-patches/build.properties")) {
                    Properties p = new Properties();
                    p.load(zin);
                    return p;
                }
            }
        }
        throw new IOException("byo.zip has no game-patches/build.properties");
    }

    void extractTo(Path dir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                Path t = dir.resolve(e.getName()).normalize();
                if (!t.startsWith(dir)) throw new IOException("bad entry " + e.getName());
                Files.createDirectories(t.getParent());
                Files.copy(zin, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
