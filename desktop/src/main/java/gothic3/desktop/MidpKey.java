package gothic3.desktop;

import java.util.LinkedHashMap;
import java.util.Map;

/** Symbolic names for the MIDP (Nokia) key codes the game understands. */
public final class MidpKey {
    private MidpKey() {}

    public static final Map<String, Integer> BY_NAME = new LinkedHashMap<>();
    static {
        BY_NAME.put("UP", -1);
        BY_NAME.put("DOWN", -2);
        BY_NAME.put("LEFT", -3);
        BY_NAME.put("RIGHT", -4);
        BY_NAME.put("FIRE", -5);
        BY_NAME.put("SOFT_LEFT", -6);
        BY_NAME.put("SOFT_RIGHT", -7);
        for (int i = 0; i <= 9; i++) BY_NAME.put("NUM" + i, 48 + i);
        BY_NAME.put("STAR", 42);
        BY_NAME.put("POUND", 35);
    }

    /** Name (e.g. FIRE, NUM5) or a raw integer code; throws IllegalArgumentException if unknown. */
    public static int parse(String s) {
        String n = s.trim().toUpperCase();
        Integer v = BY_NAME.get(n);
        if (v != null) return v;
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown MIDP key: " + s);
        }
    }
}
