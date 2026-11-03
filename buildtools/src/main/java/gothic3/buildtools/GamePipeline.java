package gothic3.buildtools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The jar -> generated game tree pipeline, shared by the build-time {@link GameSetup} (gradle setupGame) and the run-time
 * build inside the shipped app. {@code root} is a directory shaped like the repository: {@code game-patches/} (build.properties,
 * decompile.sha256, g3/*.patch), {@code docs/mapping/} and {@code tools/} (tiny-remapper, Vineflower).
 * Stages: verify jar -> remap -> decompile -> verify raw decompile hashes -> apply patches -> extract resources (+ AMR to WAV).
 */
public final class GamePipeline {
    public static final class Failure extends RuntimeException {
        public Failure(String m) { super(m); }
    }

    /** Stage reporting for progress screens. */
    public interface Progress {
        void stage(String stage, String detail);
    }

    public enum Audio { REQUIRED, OPTIONAL }

    /** What generate() produced besides the sources. */
    public static final class Result {
        public boolean audioConverted;
        public int patchFiles;
    }

    public final Path root;
    public final Path patches;
    public final Properties cfg = new Properties();

    public GamePipeline(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.patches = this.root.resolve("game-patches");
        try (InputStream in = Files.newInputStream(patches.resolve("build.properties"))) { cfg.load(in); }
    }

    // ------------------------------------------------------------------------------------------------ generate

    /**
     * Generates {@code gen/src/g3}, {@code gen/resources}, {@code gen/manifest} (and {@code gen/pristine} when keepPristine) from the jar.
     * The jar must already be verified ({@link JarCheck}); it is checked again here.
     */
    public Result generate(Path jar, Path gen, boolean keepPristine, Audio audio, Progress prog) throws Exception {
        if (prog == null) prog = (s, d) -> { };
        prog.stage("verifying", "game jar");
        JarCheck.Result jr = JarCheck.inspect(jar, cfg);
        if (jr.kind != JarCheck.Kind.OK) throw new Failure(jr.message());
        Path remapper = tool("tiny-remapper");
        Path vineflower = tool("vineflower");
        Path work = Files.createTempDirectory("g3-setup");
        Result res = new Result();
        try {
            deleteTree(gen);
            Files.createDirectories(gen);
            Path pristine = keepPristine ? gen.resolve("pristine") : work.resolve("pristine");
            decompile(jar, remapper, vineflower, work, pristine, prog);
            prog.stage("verifying", "decompiled sources");
            checkDecompile(pristine);

            prog.stage("patching", "applying game-patches");
            Path src = gen.resolve("src");
            copyTree(pristine, src);
            res.patchFiles = applyPatches(src);
            prog.stage("extracting", "game data");
            res.audioConverted = extractResources(jar, gen.resolve("resources"), gen.resolve("manifest"), audio, prog);
        } finally {
            deleteTree(work);
        }
        return res;
    }

    public int applyPatches(Path src) throws IOException {
        Path pdir = patches.resolve("g3");
        int applied = 0;
        if (Files.isDirectory(pdir)) {
            List<Path> ps;
            try (Stream<Path> s = Files.list(pdir)) {
                ps = s.filter(x -> x.getFileName().toString().endsWith(".patch")).sorted().collect(Collectors.toList());
            }
            for (Path p : ps) {
                String name = p.getFileName().toString().replaceAll("\\.patch$", "");
                Path target = src.resolve("g3").resolve(name);
                if (!Files.exists(target)) throw new Failure("patch " + p + " targets missing file " + name);
                Patcher.writeLines(target, Patcher.apply(Patcher.readLines(target), Patcher.readLines(p), name));
                applied++;
            }
        }
        return applied;
    }

    public Path tool(String key) throws IOException {
        String rel = cfg.getProperty(key + ".path");
        // Gradle builds fetch the pinned tools from Maven (buildtools/build.gradle) and point here; the run-time bundle has them under root/tools
        String dir = System.getProperty("g3.tools.dir");
        Path p = dir != null ? Paths.get(dir).resolve(Paths.get(rel).getFileName().toString()) : root.resolve(rel);
        if (!Files.isRegularFile(p)) throw new Failure("missing tool " + p);
        String want = cfg.getProperty(key + ".sha256");
        if (!sha256(p).equals(want)) throw new Failure("tool " + p + " has the wrong sha256 (expected " + want + ")");
        return p;
    }

    // ------------------------------------------------------------------------------------- remap + decompile

    void decompile(Path jar, Path remapper, Path vineflower, Path work, Path outSrc, Progress prog) throws Exception {
        prog.stage("decompiling", "preparing classes");
        List<String> classNames = new ArrayList<>();
        Path classesJar = work.resolve("classes.jar");
        try (JarFile jf = new JarFile(jar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(classesJar))) {
            List<JarEntry> es = Collections.list(jf.entries()).stream()
                .filter(e -> e.getName().endsWith(".class")).sorted(Comparator.comparing(JarEntry::getName)).collect(Collectors.toList());
            for (JarEntry e : es) {
                classNames.add(e.getName().replaceAll("\\.class$", ""));
                JarEntry ne = new JarEntry(e.getName());
                ne.setTime(0);
                jos.putNextEntry(ne);
                jf.getInputStream(e).transferTo(jos);
                jos.closeEntry();
            }
        }
        Path tiny = work.resolve("mappings.tiny");
        TinyMapping.build(root.resolve("docs/mapping"), tiny, classNames);
        prog.stage("decompiling", "remapping names");
        Path remapped = work.resolve("remapped.jar");
        run(work, "tiny-remapper", java(), "-jar", remapper.toString(), classesJar.toString(), remapped.toString(), tiny.toString(), "official", "named");
        prog.stage("decompiling", "Vineflower");
        Path dec = work.resolve("decompiled");
        Files.createDirectories(dec);
        run(work, "vineflower", java(), "-jar", vineflower.toString(), "-dgs=1", "-log=WARN", remapped.toString(), dec.toString());
        Files.createDirectories(outSrc);
        boolean found = false;
        List<Path> out;
        try (Stream<Path> s = Files.list(dec)) { out = s.collect(Collectors.toList()); }
        for (Path p : out) {
            if (p.toString().endsWith(".jar")) { unzip(p, outSrc); found = true; }
        }
        if (!found) copyTree(dec, outSrc);
        deleteTree(outSrc.resolve("META-INF"));
    }

    static String java() {
        return Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    static void run(Path dir, String what, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        p.getInputStream().transferTo(log);
        if (p.waitFor() != 0) throw new Failure(what + " failed:\n" + log.toString(StandardCharsets.UTF_8));
    }

    void checkDecompile(Path pristine) throws Exception {
        Map<String, String> want = readHashes(patches.resolve("decompile.sha256"));
        Map<String, String> have = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(pristine)) { files = s.filter(Files::isRegularFile).collect(Collectors.toList()); }
        for (Path p : files) have.put(pristine.relativize(p).toString().replace('\\', '/'), sha256(p));
        List<String> bad = new ArrayList<>();
        for (String k : want.keySet()) {
            if (!have.containsKey(k)) bad.add("missing:    " + k);
            else if (!have.get(k).equals(want.get(k))) bad.add("differs:    " + k + "  (got " + have.get(k) + ")");
        }
        for (String k : have.keySet()) if (!want.containsKey(k)) bad.add("unexpected: " + k);
        if (!bad.isEmpty())
            throw new Failure("decompile output does not match the pinned hashes (game-patches/decompile.sha256).\n  "
                + String.join("\n  ", bad)
                + "\nThe pipeline is only reproducible with the pinned tools (tiny-remapper, Vineflower) and mapping, and may vary with the JDK"
                + " (running on " + System.getProperty("java.version") + "). The raw decompile is kept in " + pristine + " for inspection.");
        System.out.println("decompile output matches pinned hashes (" + want.size() + " files)");
    }

    static Map<String, String> readHashes(Path f) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (String l : Files.readAllLines(f)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            int sp = l.indexOf("  ");
            m.put(l.substring(sp + 2), l.substring(0, sp));
        }
        return m;
    }

    // ---------------------------------------------------------------------------------------------- resources

    /** @return true when the AMR sounds were converted (always true for Audio.REQUIRED, which fails instead). */
    static boolean extractResources(Path jar, Path resOut, Path manifestOut, Audio audio, Progress prog) throws Exception {
        Files.createDirectories(resOut);
        Files.createDirectories(manifestOut);
        List<Path> amrs = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) {
                String n = e.getName();
                if (e.isDirectory() || n.endsWith(".class")) continue;
                if (n.equals("META-INF/MANIFEST.MF")) {
                    try (InputStream in = jf.getInputStream(e)) { Files.copy(in, manifestOut.resolve("MANIFEST.MF"), StandardCopyOption.REPLACE_EXISTING); }
                    continue;
                }
                if (n.startsWith("META-INF/")) continue;
                if (n.contains("..") || n.startsWith("/")) throw new Failure("suspicious jar entry " + n);
                Path t = resOut.resolve(n);
                Files.createDirectories(t.getParent());
                try (InputStream in = jf.getInputStream(e)) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
                if (n.endsWith(".amr")) amrs.add(t);
            }
        }
        Collections.sort(amrs);
        prog.stage("converting audio", amrs.size() + " sound effects");
        return AmrConverter.convert(amrs, resOut.resolve("audio"), audio == Audio.REQUIRED);
    }

    // ---------------------------------------------------------------------------------------------- utilities

    public static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static String sha1(Path p) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** SHA-256 over relative names + contents of every file below the given directories (sorted); stable across machines. */
    public static String hashTrees(Path base, String... dirs) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String d : dirs) hashTree(md, base.resolve(d));
        return hex(md.digest());
    }

    static void hashTree(MessageDigest md, Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) { files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList()); }
        for (Path p : files) {
            md.update((dir.relativize(p).toString().replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(Files.readAllBytes(p));
        }
    }

    public static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        List<Path> all;
        try (Stream<Path> s = Files.walk(p)) { all = s.sorted(Comparator.reverseOrder()).collect(Collectors.toList()); }
        for (Path q : all) Files.delete(q);
    }

    static void copyTree(Path from, Path to) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(from)) { all = s.collect(Collectors.toList()); }
        for (Path p : all) {
            Path t = to.resolve(from.relativize(p).toString());
            if (Files.isDirectory(p)) Files.createDirectories(t);
            else { Files.createDirectories(t.getParent()); Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    static void unzip(Path zip, Path to) throws IOException {
        try (JarFile jf = new JarFile(zip.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) {
                if (e.isDirectory()) continue;
                Path t = to.resolve(e.getName()).normalize();
                if (!t.startsWith(to)) throw new IOException("bad zip entry " + e.getName());
                Files.createDirectories(t.getParent());
                try (InputStream in = jf.getInputStream(e)) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }
}
