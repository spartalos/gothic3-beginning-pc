package gothic3.port;

import java.util.Random;
import javax.microedition.lcdui.Image;

/**
 * Everything the port (loop, tracing, 3D capture glue, desktop tools) needs to read or call in the game. The game module
 * implements this once (a thin accessor class) and registers it with {@link GameHooks}; nothing in this module or in
 * {@code desktop} references a game class at compile time. Getters must be side-effect free.
 */
public interface GameBridge {
    // ---- identity / reflection
    /** Class loader of the game classes (StateHasher / RenderMutations look up fields by name through it). */
    ClassLoader gameClassLoader();
    /** The game's RNG (a SnapshotRandom unless -Dg3.fixedSeed is wrong). */
    Random gameRandom();

    // ---- game loop
    boolean running();
    boolean paused();
    /** Monitor the background loader holds while it rebuilds the world, or null if there is no loader yet. */
    Object loaderLock();
    int tickCounter();
    void setTickCounter(int v);
    int gameState();
    boolean menuInputEnabled();
    int menuViewFlags();
    boolean dialogueOpen();
    boolean messageBoxOpen();
    boolean lockpickingActive();
    boolean soundDeferred();
    void playPendingSound();
    void updateInput();
    void updateState();
    /** The canvas render into its own graphics (was {@code render(screenGraphics)}). */
    void render();
    void flushGraphics();
    int[] pixelsInternal();
    /** True while an in-between (interpolated) frame is rendered: draw code must not change game state then. */
    void setIntermediateFrame(boolean v);
    /** Dev tool (-Dg3.debug=true): starts a map load if the game is in a state that allows it. */
    void warp(int area, int x, int y);

    // ---- world state (read only)
    int[] player();
    int[][] entities();
    int[][] tileGrid();
    int mapWidth();
    int mapHeight();
    int tileWidth();
    int tileHeight();
    int tilesetIds();
    int paletteTheme();
    int cameraX();
    int cameraY();
    void setCamera(int x, int y);
    int screenWidth();
    int screenHeight();
    /** Position of an entity row in 1/4096 tile units into out[0..1]. */
    void entityPos(int[] row, int[] out);

    // ---- images (3D renderer, desktop tools)
    short[] imageTable();
    void setImageTable(short[] table);
    void initImageTable();
    boolean ensureImageVariant(int tileset, int palette);
    Image[][] images();
    Image[][][] sheetImages();
}
