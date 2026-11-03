package gothic3.port;

// PORT: new file. Glue between GameEngine's world pass and the optional 3D renderer (docs/render3d.md).
// Everything here runs on the game thread, reads game state only, and never writes any of it.

import gothic3.platform.scene3d.DrawCapture;
import gothic3.platform.scene3d.MapSnapshot;
import gothic3.platform.scene3d.Scene3D;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public final class Render3DHook {
   private static GameBridge b() {
      return GameHooks.bridge();
   }

   private Render3DHook() {}

   /** Dev tool for screenshots (-Dg3.debug=true): the script runner can request a map load (command "warp area x y"). */
   public static final boolean DEBUG = Boolean.getBoolean("g3.debug");

   static void debugWarp(GameBridge b) {
      int[] w = gothic3.platform.RenderConfig.takeWarp();
      if (w != null) b.warp(w[0], w[1], w[2]);
   }

   /** Largest number of cells the 3D capture area may extend the 2D cull window on each side (performance cap). */
   static final int MAX_EXTRA = 48;
   /** Deterministic runs (traces) use the fixed margins from config only, never the GL thread's feedback (unless forced for screenshots). */
   private static final boolean FEEDBACK = !gothic3.port.Port.DETERMINISTIC || Boolean.getBoolean("g3.render3d.captureFeedback");

   /**
    * Cell rect [x0,x1) x [y0,y1) whose entities are drawn (captured) in 3D: the 2D cull window grown to what the 3D camera sees
    * (GL thread hint, RenderConfig.captureHint) or, without a hint / in deterministic mode, by the fixed config margins.
    * Render hint only: it decides which entities are drawn, never any game state (see GameEngine.renderWorld guards).
    */
   public static void extendWindow(int x0, int y0, int x1, int y1, int[] out) {
      int[] h = FEEDBACK ? gothic3.platform.RenderConfig.captureHint() : null;
      int a, b, c, d;
      if (h == null) {
         int[] m = gothic3.platform.RenderConfig.captureMargin();
         a = x0 - m[0];
         b = y0 - m[1];
         c = x1 + m[2];
         d = y1 + m[3];
      } else {
         a = Math.min(x0, h[0]);
         b = Math.min(y0, h[1]);
         c = Math.max(x1, h[2]);
         d = Math.max(y1, h[3]);
      }

      out[0] = Math.max(Math.max(0, x0 - MAX_EXTRA), a);
      out[1] = Math.max(Math.max(0, y0 - MAX_EXTRA), b);
      out[2] = Math.min(Math.min(b().mapWidth(), x1 + MAX_EXTRA), c);
      out[3] = Math.min(Math.min(b().mapHeight(), y1 + MAX_EXTRA), d);
   }

   private static MapSnapshot cached;
   private static int[][] cachedGridRef;
   private static int version;
   private static Scene3D lastScene;

   /** Start of the world pass: true if this frame is captured for the 3D renderer (the 2D pass is then suppressed). */
   public static boolean begin(Graphics g) {
      GameBridge B = b();
      int[] pl = B.player();
      if (!DrawCapture.enabled || pl == null || pl[11] == 0 || B.tileGrid() == null
         || B.imageTable() == null || B.images() == null) {
         return false;
      }

      return DrawCapture.beginWorld(g, B.cameraX(), B.cameraY(), B.screenWidth(), B.screenHeight());
   }

   /** End of renderWorld, after the world anchored overlays. */
   public static void finish() {
      GameBridge B = b();
      // camera focus = the player's feet (interpolated: during in-between frames the entity row already holds the blended position).
      // Fall back to the game camera (scripted camera moments, hidden player) when the player is not inside the 2D view.
      int[] p = B.player();
      boolean ok = p != null && p[11] != 0;
      float fx = 0, fy = 0;
      if (ok) {
         B.entityPos(p, POS);
         fx = POS[0] * (float) B.tileWidth() / 4096f;
         fy = POS[1] * (float) B.tileHeight() / 4096f;
         ok = fx >= B.cameraX() && fx <= B.cameraX() + B.screenWidth()
            && fy >= B.cameraY() && fy <= B.cameraY() + B.screenHeight();
      }

      DrawCapture.focus(ok, fx, fy);
      DrawCapture.finish(snapshot());
      lastScene = DrawCapture.pending();
   }

   /** World not drawn because a dialogue is open: keep showing the last 3D scene under the (2D) dialogue. */
   public static void keepLastScene() {
      if (DrawCapture.enabled && lastScene != null) DrawCapture.hold(lastScene);
   }

   /** Forget the held scene (map change etc. are detected by the scene's own map; this is for 3D being switched off). */
   public static void reset() {
      lastScene = null;
   }

   private static Image sheet(int tileset, int[] colsRows) {
      GameBridge B = b();
      short[] it = B.imageTable();
      if (tileset < 0 || tileset * 11 + 6 >= it.length || tileset >= B.images().length) return null;
      int pal = it[tileset * 11 + 4] == 5 ? B.paletteTheme() : 0;
      Image[] v = B.images()[tileset];
      colsRows[0] = it[tileset * 11 + 5];
      colsRows[1] = it[tileset * 11 + 6];
      return v == null || pal < 0 || pal >= v.length ? null : v[pal];
   }

   private static final int[] POS = new int[2];
   private static final int[] CR0 = new int[2], CR1 = new int[2];

   private static MapSnapshot snapshot() {
      GameBridge B = b();
      int[][] grid = B.tileGrid();
      int h = grid.length, w = grid[0].length;
      int ts0 = B.tilesetIds() & 65535, ts1 = (B.tilesetIds() & -65536) >> 16;
      Image s0 = sheet(ts0, CR0), s1 = sheet(ts1, CR1);
      int[] pl = B.player();
      int mapId = pl != null && pl.length > 11 ? pl[11] : -1;
      MapSnapshot c = cached;
      boolean same = c != null && grid == cachedGridRef && c.w == w && c.h == h && c.sheet0 == s0 && c.sheet1 == s1
         && c.tileset0 == ts0 && c.tileset1 == ts1 && c.tileW == B.tileWidth() && c.mapId == mapId;
      if (same) {
         int[] cells = c.cells;
         search:
         for (int y = 0; y < h; y++) {
            int[] row = grid[y];
            int o = y * w;
            for (int x = 0; x < w; x++) {
               if (row[x] != cells[o + x]) {
                  same = false;
                  break search;
               }
            }
         }
      }

      if (!same) {
         int[] cells = new int[w * h];
         for (int y = 0; y < h; y++) {
            System.arraycopy(grid[y], 0, cells, y * w, w);
         }

         c = new MapSnapshot(++version, w, h, B.tileWidth(), B.tileHeight(), cells, s0, s1, CR0[0], CR0[1], CR1[0], CR1[1], ts0, ts1, mapId);
         cached = c;
         cachedGridRef = grid;
      }

      return c;
   }
}
