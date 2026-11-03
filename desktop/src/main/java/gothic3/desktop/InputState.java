package gothic3.desktop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.microedition.lcdui.DisplayHost;

/**
 * Merges many physical sources (keyboard keys, pad buttons, stick directions, script) onto
 * MIDP key codes. A MIDP keyPressed is emitted only on the first source going down and a
 * keyReleased only when the last source goes up, so OS auto-repeat can never generate
 * repeated presses.
 */
public final class InputState {
    private final Map<Integer, Set<String>> down = new HashMap<>();

    public synchronized void press(String source, int midpCode) {
        Set<String> s = down.computeIfAbsent(midpCode, k -> new HashSet<>());
        if (s.add(source) && s.size() == 1) DisplayHost.postKey(midpCode, true);
    }

    public synchronized void release(String source, int midpCode) {
        Set<String> s = down.get(midpCode);
        if (s != null && s.remove(source) && s.isEmpty()) DisplayHost.postKey(midpCode, false);
    }

    public synchronized void releaseAll() {
        for (Map.Entry<Integer, Set<String>> e : down.entrySet()) {
            if (!e.getValue().isEmpty()) {
                e.getValue().clear();
                DisplayHost.postKey(e.getKey(), false);
            }
        }
    }
}
