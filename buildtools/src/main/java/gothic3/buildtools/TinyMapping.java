package gothic3.buildtools;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** docs/mapping/*.map (see FORMAT.md there) -> Tiny v2 mapping; every class moves to package g3. */
final class TinyMapping {
    private TinyMapping() {}

    static void build(Path mappingDir, Path out, List<String> allClasses) throws IOException {
        Map<String, String> classes = new HashMap<>();
        Map<String, List<String[]>> fields = new HashMap<>(), methods = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(mappingDir)) {
            files = s.filter(p -> p.toString().endsWith(".map")).sorted().collect(Collectors.toList());
        }
        for (Path p : files) {
            int no = 0;
            for (String raw : Files.readAllLines(p)) {
                no++;
                int h = raw.indexOf('#');
                String line = (h >= 0 ? raw.substring(0, h) : raw).strip();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                String where = p.getFileName() + ":" + no;
                if (t[0].equals("class") && t.length == 3) classes.put(t[1], t[2]);
                else if (t[0].equals("field") && t.length == 5) fields.computeIfAbsent(t[1], k -> new ArrayList<>()).add(new String[] {t[2], t[3], t[4]});
                else if (t[0].equals("method") && t.length == 5) methods.computeIfAbsent(t[1], k -> new ArrayList<>()).add(new String[] {t[2], t[3], t[4]});
                else throw new IOException(where + ": cannot parse: " + raw);
            }
        }
        Set<String> unknown = new TreeSet<>();
        unknown.addAll(classes.keySet());
        unknown.addAll(fields.keySet());
        unknown.addAll(methods.keySet());
        unknown.removeAll(allClasses);
        if (!unknown.isEmpty()) throw new IOException("mapping refers to unknown classes: " + unknown);
        StringBuilder sb = new StringBuilder("tiny\t2\t0\tofficial\tnamed\n");
        for (String obf : allClasses) {
            sb.append("c\t").append(obf).append("\tg3/").append(classes.getOrDefault(obf, obf)).append('\n');
            for (String[] f : fields.getOrDefault(obf, List.of()))
                sb.append("\tf\t").append(f[1]).append('\t').append(f[0]).append('\t').append(f[2]).append('\n');
            for (String[] m : methods.getOrDefault(obf, List.of()))
                sb.append("\tm\t").append(m[1]).append('\t').append(m[0]).append('\t').append(m[2]).append('\n');
        }
        Files.writeString(out, sb.toString());
    }
}
