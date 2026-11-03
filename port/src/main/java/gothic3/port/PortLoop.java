package gothic3.port;

// PORT: new file. Fixed-step game loop with display-rate interpolated rendering, plus the hooks used by
// the original (classic) loop for tick tracing / verification. See docs/analysis/render-side-effects.md.

import gothic3.port.Port;
import gothic3.port.RenderMutations;
import gothic3.port.TickInterpolation;
import gothic3.port.TickTrace;
import gothic3.platform.FramePacer;
import gothic3.platform.Platform;
import gothic3.platform.RenderConfig;

public final class PortLoop {
   /** The game's accessor, registered by the game (see GameHooks). */
   private static GameBridge B;
   private static GameBridge b() {
      GameBridge x = B;
      if (x == null) B = x = GameHooks.bridge();
      return x;
   }

   private PortLoop() {}

   /** Logic rate. Fixed, exactly the original 10 Hz. */
   static final long TICK_NS = 100_000_000L;
   /** Classic loop (game code): milliseconds per frame at the current speed. */
   public static long classicFrameMs() { return 100L / RenderConfig.speed(); }
   /** After a stall run at most this many ticks back to back, then resync the clock. */
   static final int MAX_CATCHUP = 3;

   private static long tickNo;
   private static long tickStartNs;
   /** tickCounter value the last canonical render saw (in-between frames render with it). */
   private static int canonicalTickCounter;
   private static final boolean TRACE = TickTrace.enabled();
   private static final boolean VERIFY = TickTrace.verifyEnabled();
   private static final boolean INTERP = RenderConfig.interpolated();
   private static final boolean SWAP = INTERP || VERIFY;

   // ---------------------------------------------------------------- shared tick pieces (classic + interpolated)

   public static void beginTick() {
      GameBridge B = b();
      if (!INTERP) { // classic loop: the options screen pauses here (interpolated loop pauses in run())
         while (B.running() && RenderConfig.paused()) Platform.pacer().sleepUntil(System.nanoTime() + 50_000_000L);
      }
      if (Render3DHook.DEBUG) Render3DHook.debugWarp(B); // PORT: dev only (-Dg3.debug=true), see docs/render3d.md
      tickStartNs = System.nanoTime();
      Port.setTickIndex(tickNo);
   }

   /** After updateInput/updateState/sound: deterministic loads, then snapshot for interpolation. */
   public static void afterLogic() {
      Port.runPendingLoaders();
      if (SWAP) capture();
   }

   private static void capture() {
      GameBridge B = b();
      Object lock = B.loaderLock();
      if (lock != null) {
         synchronized (lock) {
            doCapture(B);
         }
      } else {
         doCapture(B);
      }
   }

   private static void doCapture(GameBridge B) {
      int[] player = B.player();
      int key = player == null ? 0 : player[11] * 1000003 + B.mapWidth() * 4099 + B.mapHeight();
      TickInterpolation.capture(B.entities(), key, B.cameraX(), B.cameraY(), B.screenWidth());
   }

   /** The first render after a tick: identical to the original frame, including its side effects. */
   public static void renderCanonical() {
      GameBridge B = b();
      B.setIntermediateFrame(false);
      long t0 = System.nanoTime();
      if (VERIFY) TickTrace.canonicalBefore();
      if (SWAP) RenderMutations.capturePre(B.entities());
      B.render();
      if (TickTrace.frameLogEnabled() && interpolatable()) logFrame('C', 1f);
      if (SWAP) RenderMutations.capturePost(B.entities());
      if (VERIFY) TickTrace.canonicalAfter();
      canonicalTickCounter = B.tickCounter();
      RenderConfig.frameDone(false, System.nanoTime() - t0);
   }

   /** After tickCounter++: trace line, verification, tick completion signal. */
   public static void endTick() {
      GameBridge B = b();
      if (TRACE) TickTrace.tick(tickNo, B.gameState(), B.pixelsInternal());
      if (VERIFY && interpolatable()) verify();
      if (TRACE) TickTrace.commit();
      tickNo++;
      RenderConfig.tickDone(System.nanoTime() - tickStartNs);
   }

   /** True if in-between frames make sense: world is drawn, nothing modal/menu that animates by itself. */
   static boolean interpolatable() {
      GameBridge B = b();
      return B.gameState() == 6
         && !B.paused()
         && !B.menuInputEnabled()
         && B.menuViewFlags() == 0
         && !B.dialogueOpen()
         && !B.messageBoxOpen()
         && !B.lockpickingActive()
         && B.loaderLock() != null
         && B.entities() != null;
   }

   // ---------------------------------------------------------------- interpolated frames

   /**
    * Renders and (optionally) publishes one in-between frame at {@code alpha}. Returns false if the state does
    * not allow it (then nothing was drawn).
    */
   static boolean renderIntermediate(float alpha, boolean publish) {
      GameBridge B = b();
      Object lock = B.loaderLock();
      if (lock == null) return false;
      long t0 = System.nanoTime();
      synchronized (lock) { // the loader must not rebuild the world while interpolated values are in the rows
         if (!interpolatable() || !TickInterpolation.matches(B.entities())) return false;
         int camX = B.cameraX(), camY = B.cameraY(), tc = B.tickCounter();
         RenderMutations.applyPre(B.entities());   // rows/scratch exactly as the canonical render found them ...
         TickInterpolation.begin(alpha, B.entities()); // ... then the interpolated positions on top
         B.setIntermediateFrame(true);
         try {
            if (TickInterpolation.cameraInterpolated()) {
               B.setCamera(TickInterpolation.cameraX(), TickInterpolation.cameraY());
            }
            B.setTickCounter(canonicalTickCounter);
            if (TickTrace.frameLogEnabled()) logFrame('I', alpha);
            B.render();
         } finally {
            B.setIntermediateFrame(false);
            TickInterpolation.end();
            RenderMutations.applyPost(B.entities()); // what the canonical render left behind
            B.setCamera(camX, camY);
            B.setTickCounter(tc);
         }
      }
      if (publish) B.flushGraphics();
      RenderConfig.frameDone(true, System.nanoTime() - t0);
      return true;
   }

   private static void logFrame(char kind, float alpha) {
      GameBridge B = b();
      int[] p = B.player();
      TickTrace.frame(kind, tickNo, alpha, B.cameraX(), B.cameraY(),
         p[4] * B.tileWidth() >> 12, p[5] * B.tileWidth() >> 12);
   }

   private static void verify() {
      int[] px = b().pixelsInternal();
      int[] canon = px.clone();
      java.util.Map<String, Long> before = gothic3.port.StateHasher.fieldHashes();
      if (renderIntermediate(1.0f, false)) {
         TickTrace.parity(tickNo, canon, px);
         TickTrace.alpha1Hash(px);
      }
      if (renderIntermediate(0.0f, false) && renderIntermediate(0.5f, false)) {
         java.util.Map<String, Long> after = gothic3.port.StateHasher.fieldHashes();
         TickTrace.intermediateStateDiff(tickNo, before, after);
      }
      System.arraycopy(canon, 0, px, 0, canon.length); // leave the canonical frame in the buffer (it may still be published)
   }

   // ---------------------------------------------------------------- the interpolated main loop

   public static void run() {
      final GameBridge B = b();
      final FramePacer pacer = Platform.pacer();
      long nextTick = System.nanoTime();
      long lastTickTime = nextTick;
      int curSpeed = RenderConfig.speed();
      long tickNs = TICK_NS / curSpeed;
      boolean withheld = false;      // canonical frame of the latest tick was not published
      int framesThisTick = 0;
      long nextFrame = 0;            // timed pacing (maxFps)
      int curMaxFps = -1;
      long frameInterval = 0;
      while (B.running()) {
         if (RenderConfig.paused()) { // options screen: no ticks, no frames; on resume shift the schedule by the pause (no catch-up burst)
            long p0 = System.nanoTime();
            while (B.running() && RenderConfig.paused()) pacer.sleepUntil(System.nanoTime() + 50_000_000L);
            long d = System.nanoTime() - p0;
            nextTick += d;
            lastTickTime += d;
            if (nextFrame != 0) nextFrame += d;
            pacer.clear();
            continue;
         }
         if (RenderConfig.speed() != curSpeed) { // fast forward toggled: re-base the schedule, keep the position inside the current tick (no burst, no jump)
            curSpeed = RenderConfig.speed();
            long newNs = TICK_NS / curSpeed;
            long n0 = System.nanoTime();
            double frac = Math.max(0.0, Math.min(1.0, (double)(n0 - lastTickTime) / (double)tickNs));
            tickNs = newNs;
            lastTickTime = n0 - (long)(frac * newNs);
            nextTick = lastTickTime + newNs;
         }
         if (RenderConfig.maxFps() != curMaxFps) { // render.maxFps can change at run time (options screen)
            curMaxFps = RenderConfig.maxFps();
            frameInterval = curMaxFps > 0 ? 1_000_000_000L / curMaxFps : 0;
            nextFrame = 0;
         }
         long now = System.nanoTime();
         if (now >= nextTick) {
            if (withheld && framesThisTick == 0) B.flushGraphics(); // nothing was shown for the previous tick: show it
            int n = 0;
            do {
               lastTickTime = nextTick;
               nextTick += tickNs;
               n++;
               withheld = tick();
               framesThisTick = 0;
               now = System.nanoTime();
            } while (B.running() && now >= nextTick && n < MAX_CATCHUP);
            if (now >= nextTick) { // far behind (stall, debugger, suspend): do not fast-forward
               lastTickTime = now;
               nextTick = now + tickNs;
            }
            continue;
         }
         if (!withheld) { // menus, loading, pause ...: the tick frame is on screen, nothing moves in between
            pacer.sleepUntil(nextTick);
            continue;
         }
         if (pacer.followDisplay()) {
            if (!pacer.await(nextTick)) continue;
         } else {
            if (frameInterval > 0) {
               if (nextFrame > now) {
                  pacer.sleepUntil(Math.min(nextFrame, nextTick));
                  continue;
               }
               nextFrame += frameInterval; // fixed schedule: wake-up latency does not accumulate
               if (nextFrame < now) nextFrame = now + frameInterval;
            }
         }
         now = System.nanoTime();
         if (now >= nextTick) continue; // tick has priority
         float alpha = (float)(now - lastTickTime) / (float)tickNs;
         if (renderIntermediate(alpha, true)) framesThisTick++;
         else if (!interpolatable()) pacer.sleepUntil(Math.min(nextTick, now + 5_000_000L));
      }
   }

   /** One logic tick plus its canonical render. Returns true if the canonical frame was withheld (in-between frames follow). */
   private static boolean tick() {
      GameBridge B = b();
      beginTick();
      B.updateInput();
      if (!B.paused()) {
         B.updateState();
      }

      if (B.soundDeferred()) {
         B.playPendingSound();
      }

      afterLogic();
      renderCanonical();
      boolean withhold = interpolatable() && TickInterpolation.matches(B.entities());
      if (!withhold) B.flushGraphics();
      B.setTickCounter(B.tickCounter() + 1);
      if (B.tickCounter() == 100000) {
         B.setTickCounter(0);
      }
      endTick();
      return withhold;
   }
}
