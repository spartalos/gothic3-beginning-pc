package gothic3.port;

/**
 * Registry of the game's {@link GameBridge}. The game registers its bridge from a static initializer (a marked PORT hook);
 * tools that run without starting the MIDlet (the auto tagger, prop dump) trigger that initializer through
 * {@link #bridge()}, which loads the class named by {@code -Dg3.bridge.init} (default {@code g3.GameEngine}) by name.
 */
public final class GameHooks {
    private GameHooks() {}

    private static volatile GameBridge bridge;

    public static void register(GameBridge b) {
        bridge = b;
    }

    /** The registered bridge, or null if the game is not loaded (tools then skip their game-dependent parts). */
    public static GameBridge bridgeOrNull() {
        GameBridge b = bridge;
        if (b != null) return b;
        String name = System.getProperty("g3.bridge.init", "g3.GameEngine");
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(), GameHooks.class.getClassLoader()};
        for (ClassLoader l : loaders) {
            if (l == null) continue;
            try {
                Class.forName(name, true, l); // static initializer registers the bridge
                if (bridge != null) return bridge;
            } catch (ClassNotFoundException | LinkageError e) {
                // try the next loader
            }
        }
        return bridge;
    }

    public static GameBridge bridge() {
        GameBridge b = bridgeOrNull();
        if (b == null) throw new IllegalStateException("no game registered (GameHooks.register was never called)");
        return b;
    }
}
