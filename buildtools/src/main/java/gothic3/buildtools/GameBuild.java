package gothic3.buildtools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Run-time build of the game jar inside the shipped app: {@link GamePipeline#generate} (same code as gradle setupGame), then the
 * patched sources plus our own game/src/port/java are compiled in-process with javax.tools against the app's classpath
 * (midp + port), and everything is packed into one jar (classes + the jar's resources + audio + MIDlet manifest).
 */
public final class GameBuild {
    private GameBuild() {}

    public static final class Result {
        public Path gameJar;
        public boolean audioConverted;
    }

    /**
     * @param root     repo-shaped directory with game-patches/, docs/mapping/, tools/, game/src/port/java/
     * @param classpath compile classpath (java.class.path of the app)
     * @param outJar   target; written atomically
     */
    public static Result build(Path root, Path jar, Path outJar, String classpath, GamePipeline.Audio audio, GamePipeline.Progress prog) throws Exception {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new GamePipeline.Failure("This Java runtime has no compiler (module jdk.compiler is missing).");
        Files.createDirectories(outJar.toAbsolutePath().getParent());
        Path work = Files.createTempDirectory(outJar.toAbsolutePath().getParent(), ".build-");
        try {
            GamePipeline pl = new GamePipeline(root);
            Path gen = work.resolve("gen");
            GamePipeline.Result gr = pl.generate(jar, gen, false, audio, prog);

            prog.stage("compiling", "game sources");
            Path classes = work.resolve("classes");
            Files.createDirectories(classes);
            List<Path> srcs = new ArrayList<>();
            for (Path dir : new Path[] {gen.resolve("src"), root.resolve("game/src/port/java")}) {
                try (Stream<Path> s = Files.walk(dir)) { s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(srcs::add); }
            }
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
                List<String> opts = new ArrayList<>(List.of("-d", classes.toString(), "-classpath", classpath, "-encoding", "UTF-8", "-g", "-proc:none",
                    "-nowarn", "-source", "17", "-target", "17", "-Xlint:-options", "-Xlint:-removal", "-Xlint:-deprecation"));
                boolean ok = jc.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(srcs)).call();
                if (!ok) {
                    StringBuilder sb = new StringBuilder("compiling the game failed:");
                    int n = 0;
                    for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics())
                        if (d.getKind() == Diagnostic.Kind.ERROR && n++ < 15) sb.append("\n  ").append(d.getSource() == null ? "" : Paths.get(d.getSource().getName()).getFileName())
                            .append(':').append(d.getLineNumber()).append(": ").append(d.getMessage(null));
                    throw new GamePipeline.Failure(sb.toString());
                }
            }

            prog.stage("packaging", "game.jar");
            Manifest mf = new Manifest();
            Path mfFile = gen.resolve("manifest/MANIFEST.MF");
            if (Files.isRegularFile(mfFile)) try (InputStream in = Files.newInputStream(mfFile)) { mf = new Manifest(in); }
            if (mf.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION) == null) mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            Path tmp = work.resolve("game.jar");
            try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream jos = new JarOutputStream(os, mf)) {
                for (Path base : new Path[] {classes, gen.resolve("resources")}) {
                    List<Path> files;
                    try (Stream<Path> s = Files.walk(base)) { files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList()); }
                    for (Path f : files) {
                        JarEntry e = new JarEntry(base.relativize(f).toString().replace('\\', '/'));
                        e.setTime(0);
                        jos.putNextEntry(e);
                        Files.copy(f, jos);
                        jos.closeEntry();
                    }
                }
            }
            Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING);
            Result r = new Result();
            r.gameJar = outJar;
            r.audioConverted = gr.audioConverted;
            return r;
        } finally {
            GamePipeline.deleteTree(work);
        }
    }
}
