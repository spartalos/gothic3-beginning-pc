package gothic3.platform;

import java.util.function.Consumer;

/**
 * Startup questions (sound, then 2D/3D graphics): bridge between the game's menu 17 ("sound?") and the desktop config.
 * {@link #active} is only set for interactive launches (no script, no deterministic/trace/verify run), so automated runs
 * keep the original flow tick for tick. libGDX-free.
 */
public final class StartupPrefs {
    private StartupPrefs() {}

    /** Interactive launch: ask the questions once, then apply the saved answers. */
    public static volatile boolean active;
    /** Both questions were answered in an earlier run: skip them and apply the saved answers. */
    public static volatile boolean done;
    /** Saved sound answer (config audio.enabled). */
    public static volatile boolean soundEnabled = true;
    /** Ask the 2D/3D question after the sound question (false when a view flag was given on the command line). */
    public static volatile boolean askGraphics = true;

    /** The graphics question is currently shown (menu 17 with port texts). */
    public static volatile boolean gfxPhase;

    /** Presenter hint: true while the original game's settings menu is on screen (the port draws a hotkey footer over it). Read only. */
    public static volatile java.util.function.BooleanSupplier settingsMenuVisible = () -> false;

    /** Music+sfx sound mode: music has its own pending-sound slot (Gothic3Midlet.requestSound). Lives here, not in a g3 static, so the state hash is unchanged. */
    public static volatile int pendingMusicId = -1;

    /** Receives the sound answer (true = sound on); persists it. */
    public static volatile Consumer<Boolean> onSound = b -> { };
    /** Receives the graphics answer (true = 3D); applies and persists it. */
    public static volatile Consumer<Boolean> onGraphics = b -> { };
    /** All questions answered; persists the flag. */
    public static volatile Runnable onFinished = () -> { };
}
