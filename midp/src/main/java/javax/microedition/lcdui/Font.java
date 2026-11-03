package javax.microedition.lcdui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Font backed by a bundled TrueType font (Liberation Sans) rasterised through
 * java.awt WITHOUT anti-aliasing into a per-glyph 1-bit mask cache, so text is crisp
 * and deterministic. Sizes are tuned to Nokia Series 40 heights (~12/14/16 px).
 */
public final class Font {
    public static final int FACE_SYSTEM = 0, FACE_MONOSPACE = 32, FACE_PROPORTIONAL = 64;
    public static final int STYLE_PLAIN = 0, STYLE_BOLD = 1, STYLE_ITALIC = 2, STYLE_UNDERLINED = 4;
    public static final int SIZE_MEDIUM = 0, SIZE_SMALL = 8, SIZE_LARGE = 16;
    public static final int FONT_STATIC_TEXT = 0, FONT_INPUT_TEXT = 1;

    /** AWT pixel sizes for small/medium/large. */
    private static final float[] PX = {10f, 12f, 14f};

    private static final Map<Integer, Font> CACHE = new HashMap<>();
    private static java.awt.Font baseRegular, baseBold;

    static final class Glyph {
        int advance;
        int ox, oy, w, h; // bbox relative to pen position / top of line
        boolean[] bits;
    }

    private final int face, style, size;
    private final int ascent, descent, height;
    private final java.awt.Font awt;
    private final java.awt.FontMetrics fm;
    private final Glyph[] glyphs = new Glyph[65536];
    private final BufferedImage scratch;
    private final Graphics2D sg;
    private final int pad;

    private Font(int face, int style, int size) {
        this.face = face;
        this.style = style;
        this.size = size;
        loadBase();
        int idx = size == SIZE_SMALL ? 0 : size == SIZE_LARGE ? 2 : 1;
        boolean bold = (style & STYLE_BOLD) != 0;
        int awtStyle = (style & STYLE_ITALIC) != 0 ? java.awt.Font.ITALIC : java.awt.Font.PLAIN;
        awt = (bold ? baseBold : baseRegular).deriveFont(awtStyle, PX[idx]);
        pad = (int) PX[idx];
        scratch = new BufferedImage(pad * 4, pad * 3, BufferedImage.TYPE_INT_ARGB);
        sg = scratch.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        sg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        sg.setFont(awt);
        fm = sg.getFontMetrics();
        ascent = fm.getAscent();
        descent = fm.getDescent();
        height = ascent + descent + fm.getLeading();
    }

    private static synchronized void loadBase() {
        if (baseRegular != null) return;
        try {
            baseRegular = load("gothic3/platform/fonts/LiberationSans-Regular.ttf");
            baseBold = load("gothic3/platform/fonts/LiberationSans-Bold.ttf");
        } catch (Exception e) {
            throw new RuntimeException("Cannot load bundled fonts", e);
        }
    }

    private static java.awt.Font load(String res) throws Exception {
        try (InputStream in = Font.class.getClassLoader().getResourceAsStream(res)) {
            return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
        }
    }

    public static synchronized Font getFont(int face, int style, int size) {
        if (face != FACE_SYSTEM && face != FACE_MONOSPACE && face != FACE_PROPORTIONAL)
            throw new IllegalArgumentException("face");
        if (size != SIZE_SMALL && size != SIZE_MEDIUM && size != SIZE_LARGE)
            throw new IllegalArgumentException("size");
        if (style < 0 || style > 7) throw new IllegalArgumentException("style");
        int key = face << 16 | style << 8 | size;
        return CACHE.computeIfAbsent(key, k -> new Font(face, style, size));
    }

    public static Font getDefaultFont() { return getFont(FACE_SYSTEM, STYLE_PLAIN, SIZE_MEDIUM); }

    public static Font getFont(int fontSpecifier) { return getDefaultFont(); }

    public int getStyle() { return style; }
    public int getSize() { return size; }
    public int getFace() { return face; }
    public boolean isPlain() { return style == 0; }
    public boolean isBold() { return (style & STYLE_BOLD) != 0; }
    public boolean isItalic() { return (style & STYLE_ITALIC) != 0; }
    public boolean isUnderlined() { return (style & STYLE_UNDERLINED) != 0; }
    public int getHeight() { return height; }
    public int getBaselinePosition() { return ascent; }

    public int charWidth(char ch) { return glyph(ch).advance; }

    public int charsWidth(char[] ch, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > ch.length) throw new StringIndexOutOfBoundsException();
        int w = 0;
        for (int i = 0; i < length; i++) w += glyph(ch[offset + i]).advance;
        return w;
    }

    public int stringWidth(String str) {
        int w = 0;
        for (int i = 0; i < str.length(); i++) w += glyph(str.charAt(i)).advance;
        return w;
    }

    public int substringWidth(String str, int offset, int len) {
        return stringWidth(str.substring(offset, offset + len));
    }

    synchronized Glyph glyph(char c) {
        Glyph g = glyphs[c];
        if (g != null) return g;
        g = new Glyph();
        g.advance = fm.charWidth(c);
        // render at pen x = pad, baseline y = pad + ascent
        sg.setComposite(java.awt.AlphaComposite.Clear);
        sg.fillRect(0, 0, scratch.getWidth(), scratch.getHeight());
        sg.setComposite(java.awt.AlphaComposite.SrcOver);
        sg.setColor(java.awt.Color.WHITE);
        int baseY = pad + ascent;
        sg.drawString(String.valueOf(c), pad, baseY);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        int sw = scratch.getWidth(), sh = scratch.getHeight();
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++)
                if ((scratch.getRGB(x, y) >>> 24) >= 128) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX >= 0) {
            g.w = maxX - minX + 1;
            g.h = maxY - minY + 1;
            g.ox = minX - pad;
            g.oy = minY - pad; // relative to top of line (pad offsets the line top)
            g.bits = new boolean[g.w * g.h];
            for (int y = 0; y < g.h; y++)
                for (int x = 0; x < g.w; x++)
                    g.bits[y * g.w + x] = (scratch.getRGB(minX + x, minY + y) >>> 24) >= 128;
        }
        glyphs[c] = g;
        return g;
    }

    /** Draws glyph pixels; (penX, top) is the top-left of the text line. */
    void drawGlyphs(Graphics gr, String s, int penX, int top) {
        for (int i = 0; i < s.length(); i++) {
            Glyph g = glyph(s.charAt(i));
            if (g.bits != null) {
                int gx = penX + g.ox, gy = top + g.oy;
                for (int y = 0; y < g.h; y++)
                    for (int x = 0; x < g.w; x++)
                        if (g.bits[y * g.w + x]) gr.plot(gx + x, gy + y);
            }
            penX += g.advance;
        }
        if ((style & STYLE_UNDERLINED) != 0) {
            int w = 0;
            for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)).advance;
            gr.hline(penX - w, penX - 1, top + ascent + 1);
        }
    }
}
