package gothic3.desktop.options;

import com.badlogic.gdx.Gdx;
import gothic3.desktop.DesktopLauncher;

/** Fullscreen / window size / vsync / frame pacing, shared by F11 / Alt+Enter and the options screen. Render thread. */
public final class WindowControl {
    private final int logicalW, logicalH;
    private int scale;
    private boolean vsync;
    private boolean classic;

    public WindowControl(int logicalW, int logicalH, int scale, boolean vsync, boolean classic) {
        this.logicalW = logicalW;
        this.logicalH = logicalH;
        this.scale = scale;
        this.vsync = vsync;
        this.classic = classic;
    }

    public boolean isFullscreen() { return Gdx.graphics.isFullscreen(); }

    public void toggleFullscreen() {
        if (Gdx.graphics.isFullscreen()) Gdx.graphics.setWindowedMode(logicalW * scale, logicalH * scale);
        else Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
    }

    public void setFullscreen(boolean on) {
        if (on != isFullscreen()) toggleFullscreen();
    }

    /** window.scale value ("auto" or N): resizes the window if it is windowed. */
    public void setScale(String cfgValue) {
        scale = DesktopLauncher.autoScale(cfgValue, logicalW, logicalH);
        if (!Gdx.graphics.isFullscreen()) Gdx.graphics.setWindowedMode(logicalW * scale, logicalH * scale);
    }

    public void setVsync(boolean on) {
        vsync = on;
        Gdx.graphics.setVSync(on);
    }

    /** Presenter frame cap after a vsync / render.maxFps change (same rule as the launcher). */
    public void applyPacing(int maxFps) {
        int fps;
        if (classic) fps = 60;
        else if (vsync) fps = 0;
        else fps = maxFps > 0 ? maxFps : Math.max(60, Gdx.graphics.getDisplayMode().refreshRate);
        ((com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics) Gdx.graphics).setForegroundFPS(fps);
        gothic3.platform.RenderConfig.setMaxFps(maxFps);
        gothic3.platform.Platform.pacer().setFollowDisplay(!classic && maxFps == 0);
        gothic3.platform.Platform.pacer().wake();
    }
}
