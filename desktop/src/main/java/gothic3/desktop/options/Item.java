package gothic3.desktop.options;

import java.util.Locale;
import java.util.function.Supplier;

/** One row of the options screen. UI-free: the overlay asks for text / fraction and forwards navigation. */
abstract class Item {
    final String label;
    String hint = "";
    /** Takes effect only after a restart (the overlay shows a "Restart required" note once it differs from the launch value). */
    boolean restart;
    String startText;

    Item(String label) { this.label = label; }

    boolean selectable() { return true; }
    abstract String text();
    /** Left / right (-1 / +1). */
    void step(int dir) { }
    /** Enter / click. */
    void activate() { }
    boolean isSlider() { return false; }
    float frac() { return 0; }
    void setFrac(float f) { }
    /** Config key reset by "Reset to defaults" (null = none). */
    String key() { return null; }
    void reset() { }

    Item restart() { restart = true; return this; }
    Item hint(String h) { hint = h; return this; }

    // ------------------------------------------------------------------------------------------------

    static final class Header extends Item {
        Header(String t) { super(t); }
        @Override boolean selectable() { return false; }
        @Override String text() { return ""; }
    }

    static final class Action extends Item {
        final Runnable run;
        boolean danger;
        Action(String label, Runnable run) { super(label); this.run = run; }
        Action danger() { danger = true; return this; }
        @Override String text() { return ""; }
        @Override void activate() { run.run(); }
    }

    /** One MIDP key with two binding slots (keyboard: key.X, gamepad: pad.X). */
    static final class Bind extends Item {
        final String midp;
        final boolean pad;
        Bind(String midp, String label, boolean pad) { super(label); this.midp = midp; this.pad = pad; }
        @Override String text() { return ""; }
        @Override String key() { return (pad ? "pad." : "key.") + midp; }
    }

    /** A config value: a choice from a fixed list, or a numeric slider. */
    static final class Setting extends Item {
        final String key;
        final OptionsMenu menu;
        String[] values, shown;
        double min, max, step;
        int decimals;
        Supplier<String> live;     // current value when it does not come from the config (fullscreen, 2D/3D view)

        Setting(OptionsMenu menu, String label, String key) {
            super(label);
            this.menu = menu;
            this.key = key;
        }

        Setting choice(String[] values, String[] shown) {
            this.values = values;
            this.shown = shown;
            return this;
        }

        Setting toggle() { return choice(new String[] {"false", "true"}, new String[] {"off", "on"}); }

        Setting slider(double min, double max, double step) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
            return this;
        }

        Setting live(Supplier<String> s) { live = s; return this; }

        String raw() { return live != null ? live.get() : menu.cfg.get(key); }

        private int index() {
            String r = raw();
            for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(r)) return i;
            return -1;
        }

        private double number() {
            try { return Double.parseDouble(raw()); } catch (NumberFormatException e) { return min; }
        }

        private String fmt(double v) { return String.format(Locale.ROOT, "%." + decimals + "f", v); }

        @Override String key() { return key; }
        @Override boolean isSlider() { return values == null; }

        @Override String text() {
            if (values == null) return fmt(number());
            int i = index();
            return i < 0 ? raw() : shown[i];
        }

        @Override void step(int dir) {
            if (values != null) {
                int i = index();
                int n = values.length;
                i = i < 0 ? (dir > 0 ? 0 : n - 1) : (i + dir + n) % n;
                menu.change(key, values[i]);
            } else {
                double v = Math.max(min, Math.min(max, Math.round((number() + dir * step) / step) * step));
                menu.change(key, fmt(v));
            }
        }

        @Override void activate() { if (values != null) step(1); }

        @Override float frac() { return values != null ? 0 : (float) ((Math.max(min, Math.min(max, number())) - min) / (max - min)); }

        @Override void setFrac(float f) {
            double v = min + Math.max(0, Math.min(1, f)) * (max - min);
            v = Math.max(min, Math.min(max, Math.round(v / step) * step));
            String s = fmt(v);
            if (!s.equals(fmt(number()))) menu.change(key, s);
        }

        @Override void reset() {
            String d = defaultOf(key);
            if (d != null) menu.change(key, d);
        }

        private static String defaultOf(String k) { return gothic3.desktop.Config.defaultOf(k); }
    }
}
