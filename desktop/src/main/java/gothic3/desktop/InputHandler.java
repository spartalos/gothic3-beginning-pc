package gothic3.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.ControllerMapping;
import com.badlogic.gdx.controllers.Controllers;
import java.util.HashMap;
import java.util.Map;

/** Maps libGDX keyboard and gamepad events onto MIDP key codes using {@link Config}. */
public final class InputHandler extends InputAdapter {
    private final InputState state;
    private final Map<Integer, Integer> keyMap = new HashMap<>();            // gdx key -> midp code
    private final Map<String, Integer> padMap = new HashMap<>();             // button name -> midp code
    private volatile boolean stickEnabled;
    private volatile float deadzone;
    private volatile int ffKey = Input.Keys.F8;
    private volatile String ffPad = "R_STICK";
    private Config ffConfig;
    // in-game options screen (F10 / Start+Back): while open it gets all keyboard / mouse / gamepad input instead of the game
    private gothic3.desktop.options.OptionsOverlay options;
    private boolean startHeld, backHeld;
    private final Runnable toggleFullscreen;
    private Runnable toggleOverlay = () -> { };
    private Runnable toggleView3d = () -> { }, reload3d = () -> { };
    // tile editor (F7, docs/render3d.md): while open it gets all keyboard / mouse input instead of the game
    private Runnable toggleEditor = () -> { };
    private gothic3.desktop.render3d.editor.TileEditor editor;
    // stick direction state per controller axis: [x dir, y dir] as -1/0/1
    private int stickX, stickY;

    public InputHandler(Config cfg, InputState state, Runnable toggleFullscreen) {
        this.state = state;
        this.toggleFullscreen = toggleFullscreen;
        reloadBindings(cfg);
    }

    /** Re-reads key.* / pad.* / pad.stick.* from {@code cfg} (also used live by the options screen). Render thread. */
    public void reloadBindings(Config cfg) {
        keyMap.clear();
        padMap.clear();
        for (Map.Entry<String, Integer> e : MidpKey.BY_NAME.entrySet()) {
            for (String k : cfg.list("key." + e.getKey())) {
                int gk = keyByName(k);
                if (gk < 0) System.err.println("[input] unknown key name in config: " + k);
                else keyMap.put(gk, e.getValue());
            }
            for (String b : cfg.list("pad." + e.getKey())) padMap.put(b.toUpperCase(), e.getValue());
        }
        stickEnabled = cfg.getBool("pad.stick.enabled");
        deadzone = cfg.getFloat("pad.stick.deadzone", 0.5f);
        String fk = cfg.props.getProperty("fastforward.key", "F8").trim();
        ffKey = fk.isEmpty() ? -1 : keyByName(fk);
        if (!fk.isEmpty() && ffKey < 0) System.err.println("[input] unknown key name in config: fastforward.key = " + fk);
        ffPad = cfg.props.getProperty("fastforward.pad", "R_STICK").trim().toUpperCase();
        ffConfig = cfg;
    }

    /** Fast forward toggle: speed 1 <-> game.fastForwardSpeed (2..8). Never reaches the game. */
    public void toggleFastForward() {
        int cur = gothic3.platform.RenderConfig.speed();
        int target = ffConfig == null ? 3 : Math.max(2, Math.min(8, ffConfig.getInt("game.fastForwardSpeed", 3)));
        gothic3.platform.RenderConfig.setSpeed(cur > 1 ? 1 : target);
    }

    /** F10 (fixed) / Start+Back on a gamepad: the options screen; while it is open it gets all input. */
    public InputHandler optionsScreen(gothic3.desktop.options.OptionsOverlay o) { options = o; return this; }

    /** MIDP code a keyboard key is currently bound to (0 = none); tests. */
    public int midpFor(int gdxKey) {
        Integer c = keyMap.get(gdxKey);
        return c == null ? 0 : c;
    }

    // first-run setup screen (bring-your-own-jar): while it is up it gets all keyboard / gamepad input
    private gothic3.desktop.byo.SetupScreen setup;
    public InputHandler setupScreen(gothic3.desktop.byo.SetupScreen s) { setup = s; return this; }
    private boolean setupActive() { return setup != null && setup.active(); }

    private boolean optionsOpen() { return options != null && options.isOpen(); }

    /** Releases everything the game thinks is held (keys, pad buttons, stick), e.g. when the options screen opens. */
    public void releaseAllInput() {
        state.releaseAll();
        stickX = stickY = 0;
    }

    /** F3 (fixed): FPS / tick overlay, drawn by the presenter. */
    public InputHandler overlayToggle(Runnable r) { toggleOverlay = r; return this; }

    /** F5 (fixed): 2D / 3D view, F6 (fixed): reload the 3D tile table. */
    public InputHandler view3dKeys(Runnable toggle, Runnable reload) { toggleView3d = toggle; reload3d = reload; return this; }

    /** F7 (fixed): tile editor; while it is open the keyboard / mouse go to {@code ed} (not the game). */
    public InputHandler editorKeys(Runnable toggle, gothic3.desktop.render3d.editor.TileEditor ed) { toggleEditor = toggle; editor = ed; return this; }

    private boolean editorOpen() { return editor != null && editor.isOpen(); }

    private static Map<String, Integer> names;

    /** Case-insensitive; spaces/underscores ignored (NUMPAD_0 == "Numpad 0"). */
    public static int keyByName(String n) {
        if (names == null) {
            names = new HashMap<>();
            for (int i = 0; i < 256; i++) {
                String s; try { s = Input.Keys.toString(i); } catch (RuntimeException e) { continue; }
                if (s != null) names.putIfAbsent(norm(s), i);
            }
        }
        Integer v = names.get(norm(n));
        if (v != null) return v;
        try { // constant name, e.g. NUMPAD_DIVIDE, BACKSPACE
            return Input.Keys.class.getField(n.trim().toUpperCase().replace(' ', '_')).getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static String norm(String s) { return s.replace("_", "").replace(" ", "").toUpperCase(); }

    public void installControllers() {
        Controllers.addListener(new ControllerAdapter() {
            @Override public void connected(Controller c) { System.err.println("[input] controller connected: " + c.getName()); }
            @Override public void disconnected(Controller c) {
                System.err.println("[input] controller disconnected: " + c.getName());
                setStick(0, 0);
            }
            @Override public boolean buttonDown(Controller c, int button) { return pad(c, button, true); }
            @Override public boolean buttonUp(Controller c, int button) { return pad(c, button, false); }
            @Override public boolean axisMoved(Controller c, int axis, float value) {
                ControllerMapping m = c.getMapping();
                if (setupActive()) {
                    int d = value > 0.5f ? 1 : value < -0.5f ? -1 : 0;
                    if (axis == m.axisLeftX) { stickX = d; setup.padStick(stickX, stickY); }
                    else if (axis == m.axisLeftY) { stickY = d; setup.padStick(stickX, stickY); }
                    return true;
                }
                if (optionsOpen()) { // the options screen always accepts the left stick (fixed 0.5 dead zone)
                    int d = value > 0.5f ? 1 : value < -0.5f ? -1 : 0;
                    if (axis == m.axisLeftX) { stickX = d; options.padStick(stickX, stickY); }
                    else if (axis == m.axisLeftY) { stickY = d; options.padStick(stickX, stickY); }
                    return true;
                }
                if (!stickEnabled) return false;
                int dir = value > deadzone ? 1 : value < -deadzone ? -1 : 0;
                if (axis == m.axisLeftX) setStick(dir, stickY);
                else if (axis == m.axisLeftY) setStick(stickX, dir);
                return true;
            }
        });
    }

    private void setStick(int x, int y) {
        if (x != stickX) {
            if (stickX != 0) state.release("stick", stickX < 0 ? -3 : -4);
            if (x != 0) state.press("stick", x < 0 ? -3 : -4);
            stickX = x;
        }
        if (y != stickY) {
            if (stickY != 0) state.release("stick", stickY < 0 ? -1 : -2);
            if (y != 0) state.press("stick", y < 0 ? -1 : -2);
            stickY = y;
        }
    }

    private boolean pad(Controller c, int button, boolean down) {
        return padButton(buttonName(c.getMapping(), button), down);
    }

    /** One gamepad button event by name (A B X Y BACK START L1 R1 L_STICK R_STICK DPAD_*); also used by the script runner. */
    public boolean padButton(String name, boolean down) {
        if (name == null) return false;
        if (setupActive()) { setup.padButton(name, down); return true; }
        if (name.equals("START")) startHeld = down;
        else if (name.equals("BACK")) backHeld = down;
        if (down && startHeld && backHeld && options != null && !optionsOpen()) { // Start + Back opens the options screen
            options.open();
            return true;
        }
        if (optionsOpen()) {
            options.padButton(name, down);
            return true;
        }
        if (!ffPad.isEmpty() && name.equals(ffPad)) {
            if (down) toggleFastForward();
            return true;
        }
        Integer code = padMap.get(name);
        if (code == null) return false;
        if (down) state.press("pad:" + name, code);
        else state.release("pad:" + name, code);
        return true;
    }

    private static String buttonName(ControllerMapping m, int b) {
        if (b == m.buttonA) return "A";
        if (b == m.buttonB) return "B";
        if (b == m.buttonX) return "X";
        if (b == m.buttonY) return "Y";
        if (b == m.buttonBack) return "BACK";
        if (b == m.buttonStart) return "START";
        if (b == m.buttonL1) return "L1";
        if (b == m.buttonR1) return "R1";
        if (b == m.buttonLeftStick) return "L_STICK";
        if (b == m.buttonRightStick) return "R_STICK";
        if (b == m.buttonDpadUp) return "DPAD_UP";
        if (b == m.buttonDpadDown) return "DPAD_DOWN";
        if (b == m.buttonDpadLeft) return "DPAD_LEFT";
        if (b == m.buttonDpadRight) return "DPAD_RIGHT";
        return null;
    }

    @Override
    public boolean keyDown(int keycode) {
        if (setupActive() && keycode != Input.Keys.F11) { setup.keyDown(keycode); return true; }
        if (options != null && options.capturing()) { // "press a key..." capture: every key belongs to the options screen
            options.keyDown(keycode);
            return true;
        }
        if (keycode == Input.Keys.F11
                || (keycode == Input.Keys.ENTER && (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)))) {
            toggleFullscreen.run();
            return true;
        }
        if (keycode == ffKey) {
            toggleFastForward();
            return true;
        }
        if (keycode == Input.Keys.F3) {
            toggleOverlay.run();
            return true;
        }
        if (keycode == Input.Keys.F5) {
            toggleView3d.run();
            return true;
        }
        if (keycode == Input.Keys.F6) {
            reload3d.run();
            return true;
        }
        if (keycode == Input.Keys.F10 && options != null) {
            if (!editorOpen()) options.toggle();
            return true;
        }
        if (keycode == Input.Keys.F7) {
            if (!optionsOpen()) toggleEditor.run();
            return true;
        }
        if (optionsOpen()) {
            options.keyDown(keycode);
            return true; // the options screen swallows every key
        }
        if (editorOpen()) {
            if (!editor.keyDown(keycode)) camKey(keycode);
            return true; // the editor swallows every key
        }
        if (camKey(keycode)) return true;
        Integer code = keyMap.get(keycode);
        if (code == null) return false;
        state.press("key:" + keycode, code);
        return true;
    }

    /** 3D camera tweaks (only while the 3D view is on): PAGE_UP/PAGE_DOWN pitch, MINUS/EQUALS/PLUS (+ mouse wheel) distance, LEFT/RIGHT_BRACKET fov, HOME reset. */
    private static boolean camKey(int k) {
        if (!gothic3.platform.RenderConfig.view3d()) return false;
        switch (k) {
            case Input.Keys.PAGE_UP: gothic3.desktop.render3d.Renderer3D.adjust(2f, 0, 0); return true;
            case Input.Keys.PAGE_DOWN: gothic3.desktop.render3d.Renderer3D.adjust(-2f, 0, 0); return true;
            case Input.Keys.MINUS: gothic3.desktop.render3d.Renderer3D.adjust(0, 1f, 0); return true;
            case Input.Keys.EQUALS: case Input.Keys.PLUS: gothic3.desktop.render3d.Renderer3D.adjust(0, -1f, 0); return true;
            case Input.Keys.LEFT_BRACKET: gothic3.desktop.render3d.Renderer3D.adjust(0, 0, -2f); return true;
            case Input.Keys.RIGHT_BRACKET: gothic3.desktop.render3d.Renderer3D.adjust(0, 0, 2f); return true;
            case Input.Keys.HOME: gothic3.desktop.render3d.Renderer3D.resetCamera(); return true;
            default: return false;
        }
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (optionsOpen()) { options.scrolled(amountY); return true; }
        if (editorOpen() && editor.scrolled(amountX, amountY)) return true;
        if (!gothic3.platform.RenderConfig.view3d()) return false;
        gothic3.desktop.render3d.Renderer3D.adjust(0, amountY, 0);
        return true;
    }

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        if (optionsOpen()) return true; // polled by the options screen
        return editorOpen() && editor.touchDown(x, y, pointer, button);
    }

    @Override
    public boolean touchUp(int x, int y, int pointer, int button) { return editorOpen() || optionsOpen(); }

    @Override
    public boolean keyUp(int keycode) {
        if (keycode == Input.Keys.F7 || keycode == Input.Keys.F10 || keycode == ffKey) return true;
        if (optionsOpen()) return true; // everything was released when it opened
        if (editorOpen()) { // keys pressed before the editor opened must still be released for the game
            Integer c = keyMap.get(keycode);
            if (c != null) state.release("key:" + keycode, c);
            return true;
        }
        if (gothic3.platform.RenderConfig.view3d() && (keycode == Input.Keys.PAGE_UP || keycode == Input.Keys.PAGE_DOWN || keycode == Input.Keys.MINUS
                || keycode == Input.Keys.EQUALS || keycode == Input.Keys.PLUS || keycode == Input.Keys.LEFT_BRACKET
                || keycode == Input.Keys.RIGHT_BRACKET || keycode == Input.Keys.HOME)) return true;
        if (keycode == Input.Keys.F3 || keycode == Input.Keys.F5 || keycode == Input.Keys.F6) return true;
        Integer code = keyMap.get(keycode);
        if (code == null) return false;
        state.release("key:" + keycode, code);
        return true;
    }
}
