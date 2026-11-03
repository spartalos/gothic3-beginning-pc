package g3;

// PORT: new file. Thin accessor class: the only place where our port code (module `port`, package gothic3.port) and the desktop tools
// touch game state. Registered from GameEngine's static initializer. No logic here, only field reads / forwards.

import gothic3.port.GameBridge;
import java.util.Random;
import javax.microedition.lcdui.Image;

final class PortBridge implements GameBridge {
   static final PortBridge INSTANCE = new PortBridge();

   private PortBridge() {}

   private static GameEngine g() {
      return Gothic3Midlet.canvas;
   }

   public ClassLoader gameClassLoader() { return PortBridge.class.getClassLoader(); }
   public Random gameRandom() { return Gothic3Midlet.RANDOM; }

   public boolean running() { return GameEngine.running; }
   public boolean paused() { return GameEngine.paused; }
   public Object loaderLock() {
      LoadTask lt = GameEngine.loaderTask;
      return lt == null ? null : lt.lock;
   }
   public int tickCounter() { return GameEngine.tickCounter; }
   public void setTickCounter(int v) { GameEngine.tickCounter = v; }
   public int gameState() { return GameEngine.gameState; }
   public boolean menuInputEnabled() { return Gothic3Midlet.menuInputEnabled; }
   public int menuViewFlags() { return Gothic3Midlet.menuViewFlags; }
   public boolean dialogueOpen() { return GameEngine.dialogueOpen; }
   public boolean messageBoxOpen() { return GameEngine.messageBoxOpen; }
   public boolean lockpickingActive() { return GameEngine.lockpickingActive; }
   public boolean soundDeferred() { return Gothic3Midlet.soundDeferred; }
   public void playPendingSound() { Gothic3Midlet.playPendingSound(); }
   public void updateInput() { GameEngine.updateInput(); }
   public void updateState() { g().updateState(); }
   public void render() { GameEngine g = g(); g.render(g.screenGraphics); }
   public void flushGraphics() { g().flushGraphics(); }
   public int[] pixelsInternal() { return g().pixelsInternal(); }
   public void setIntermediateFrame(boolean v) { GameEngine.portIntermediateFrame = v; }
   public void warp(int area, int x, int y) {
      if (GameEngine.loaderTask != null && Gothic3Midlet.canvas != null && GameEngine.gameState == 6
         && !Gothic3Midlet.menuInputEnabled && !GameEngine.dialogueOpen && !GameEngine.messageBoxOpen) {
         GameEngine.loaderTask.setupMapLoad(Gothic3Midlet.canvas, area, x, y, false);
         gothic3.port.Port.startLoader(GameEngine.loaderTask);
      }
   }

   public int[] player() { return GameEngine.player; }
   public int[][] entities() { return GameWorld.entities; }
   public int[][] tileGrid() { return GameWorld.tileGrid; }
   public int mapWidth() { return GameWorld.mapWidth; }
   public int mapHeight() { return GameWorld.mapHeight; }
   public int tileWidth() { return GameWorld.tileWidth; }
   public int tileHeight() { return GameWorld.tileHeight; }
   public int tilesetIds() { return GameWorld.tilesetIds; }
   public int paletteTheme() { return GameWorld.paletteTheme; }
   public int cameraX() { return GameEngine.cameraX; }
   public int cameraY() { return GameEngine.cameraY; }
   public void setCamera(int x, int y) { GameEngine.cameraX = x; GameEngine.cameraY = y; }
   public int screenWidth() { return GameEngine.screenWidth; }
   public int screenHeight() { return GameEngine.screenHeight; }
   public void entityPos(int[] row, int[] out) { GameWorld.getEntityPos(row, out); }

   public short[] imageTable() { return GameEngine.imageTable; }
   public void setImageTable(short[] table) { GameEngine.imageTable = table; }
   public void initImageTable() { GameEngine.initImageTable(); }
   public boolean ensureImageVariant(int tileset, int palette) { return GameEngine.ensureImageVariant(tileset, palette); }
   public Image[][] images() { return GameEngine.images; }
   public Image[][][] sheetImages() { return GameEngine.sheetImages; }
}
