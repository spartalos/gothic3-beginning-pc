package gothic3.buildtools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Identifies a user-supplied game jar against the whitelist in game-patches/build.properties. */
public final class JarCheck {
    private JarCheck() {}

    public enum Kind { OK, KNOWN_WRONG, UNKNOWN, UNREADABLE }

    public static final class Result {
        public Kind kind;
        /** Name of the recognised wrong build ("452x416 build"), else null. */
        public String knownName;
        public String sha = "", expectedSha, expectedName;
        public long size, expectedSize;
        public String detail = "";

        public String message() {
            switch (kind) {
                case OK: return "OK";
                case UNREADABLE: return "Cannot read the file: " + detail;
                case KNOWN_WRONG:
                    return "This is the " + knownName + ", which is not supported. " + found() + "\n" + expected();
                default:
                    return "This is not the supported game jar (unknown build). " + found() + "\n" + expected();
            }
        }

        public String found() { return "Found: " + size + " bytes, SHA-256 " + sha + "."; }

        public String expected() {
            return "Expected: " + expectedName + ", " + String.format(java.util.Locale.ROOT, "%,d", expectedSize) + " bytes, SHA-256 " + expectedSha + ".";
        }
    }

    public static long expectedSize(Properties cfg) { return Long.parseLong(cfg.getProperty("jar.size")); }

    /** Cheap pre-filter for the auto-scan: only files with exactly the expected size are worth hashing. */
    public static boolean plausible(Path f, Properties cfg) {
        try {
            return f.getFileName().toString().toLowerCase().endsWith(".jar") && Files.isRegularFile(f) && Files.size(f) == expectedSize(cfg);
        } catch (IOException e) {
            return false;
        }
    }

    public static Result inspect(Path f, Properties cfg) {
        Result r = new Result();
        r.expectedSha = cfg.getProperty("jar.sha256");
        r.expectedSize = expectedSize(cfg);
        r.expectedName = cfg.getProperty("jar.name");
        try {
            r.size = Files.size(f);
            if (r.size > 64L << 20) { r.kind = Kind.UNKNOWN; r.sha = "(not computed, file too large)"; return r; }
            r.sha = GamePipeline.sha256(f);
        } catch (IOException e) {
            r.kind = Kind.UNREADABLE;
            r.detail = e.toString();
            return r;
        }
        if (r.sha.equals(r.expectedSha) && r.size == r.expectedSize) { r.kind = Kind.OK; return r; }
        for (int i = 1; cfg.getProperty("known." + i + ".sha256") != null; i++) {
            if (r.sha.equals(cfg.getProperty("known." + i + ".sha256"))) {
                r.kind = Kind.KNOWN_WRONG;
                r.knownName = cfg.getProperty("known." + i + ".name");
                return r;
            }
        }
        r.kind = Kind.UNKNOWN;
        return r;
    }
}
