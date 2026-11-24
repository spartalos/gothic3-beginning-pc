package gothic3.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import gothic3.platform.TextLayer;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.microedition.lcdui.Font;

/**
 * HD text presenter: draws the {@link TextLayer.Op}s of the shown frame at window resolution, on top of the upscaled picture
 * (docs/platform.md "HD text"). Glyphs are rasterised with anti-aliasing by java.awt (the same Liberation Sans outlines the game's
 * Font uses) at the real scale into texture atlas pages. Layout comes from the game's own integer advances and line metrics, scaled,
 * so nothing moves compared to the low-resolution text. Render thread only.
 */
final class HdText {
    private static final int PAGE = 1024;

    private static final class Glyph {
        TextureRegion region;
        int pad;
    }

    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private final ArrayList<Texture> pages = new ArrayList<>();
    private int penX, penY, rowH;           // shelf packer in the newest page
    private Texture white;
    private static final java.awt.font.FontRenderContext FRC = new java.awt.font.FontRenderContext(null, true, true);
    private final Map<String, float[]> layouts = new HashMap<>();

    /** Natural (fractional) pen positions of the HD font at each char boundary of s (length n+1), in window pixels. */
    private float[] natural(Font f, String s, float px) {
        int px4 = Math.round(px * 4);
        String key = (f.getFace() << 16 | f.getStyle() << 8 | f.getSize()) + ":" + px4 + ":" + s;
        float[] r = layouts.get(key);
        if (r == null) {
            r = new float[s.length() + 1];
            try {
                java.awt.font.GlyphVector gv = f.awtFont(px4 / 4f).createGlyphVector(FRC, s);
                for (int i = 0; i <= s.length(); i++) r[i] = (float) gv.getGlyphPosition(i).getX();
            } catch (RuntimeException e) {
                for (int i = 0; i <= s.length(); i++) r[i] = 0;
            }
            if (layouts.size() > 4096) layouts.clear();
            layouts.put(key, r);
        }
        return r;
    }

    private static long key(Font f, char c, int px4) {
        return ((long) (f.getFace() << 16 | f.getStyle() << 8 | f.getSize()) << 40) | ((long) px4 << 16) | c;
    }

    private Glyph glyph(Font f, char c, float px, int ascPx, int cellH, int pad) {
        int px4 = Math.round(px * 4);
        long k = key(f, c, px4);
        Glyph g = glyphs.get(k);
        if (g != null) return g;
        int adv = f.charWidth(c);
        int cw = (int) Math.ceil(adv * px / f.nominalPx()) + 2 * pad;
        BufferedImage bi = new BufferedImage(cw, cellH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = bi.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setFont(f.awtFont(px4 / 4f));
        g2.setColor(java.awt.Color.WHITE);
        g2.drawString(String.valueOf(c), pad, pad + ascPx);
        g2.dispose();
        Pixmap pm = new Pixmap(cw, cellH, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < cellH; y++)
            for (int x = 0; x < cw; x++) {
                int a = bi.getRGB(x, y) >>> 24;
                pm.drawPixel(x, y, 0xFFFFFF00 | a);   // white, coverage in alpha: tinted by the batch colour
            }
        if (penX + cw > PAGE) { penX = 0; penY += rowH; rowH = 0; }
        if (pages.isEmpty() || penY + cellH > PAGE) newPage();
        Texture page = pages.get(pages.size() - 1);
        page.draw(pm, penX, penY);
        g = new Glyph();
        g.region = new TextureRegion(page, penX, penY, cw, cellH);
        g.pad = pad;
        penX += cw;
        rowH = Math.max(rowH, cellH);
        pm.dispose();
        glyphs.put(k, g);
        return g;
    }

    private void newPage() {
        if (pages.size() >= 12) clear(); // pathological (many window sizes): start over
        Pixmap pm = new Pixmap(PAGE, PAGE, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        pages.add(t);
        penX = 0;
        penY = 0;
        rowH = 0;
    }

    private void clear() {
        for (Texture t : pages) t.dispose();
        pages.clear();
        glyphs.clear();
        penX = penY = rowH = 0;
    }

    private Texture white() {
        if (white == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(1, 1, 1, 1);
            pm.fill();
            white = new Texture(pm);
            pm.dispose();
        }
        return white;
    }

    /**
     * Draws the ops. (ox, oy, dw, dh) is the rectangle (GL window coordinates, origin bottom-left) the logical frame of fw x fh
     * pixels is shown in; bw x bh is the window (back buffer).
     */
    void draw(SpriteBatch batch, TextLayer.Op[] ops, float ox, float oy, float dw, float dh, int fw, int fh, int bw, int bh) {
        if (ops == null || ops.length == 0 || fw <= 0 || fh <= 0) return;
        float k = dw / fw;
        batch.getProjectionMatrix().setToOrtho2D(0, 0, bw, bh);
        batch.begin();
        boolean scissor = false;
        int sx0 = 0, sy0 = 0, sx1 = 0, sy1 = 0;
        for (TextLayer.Op o : ops) {
            Font f = o.font;
            int tw = f.stringWidth(o.text);
            // scissor to the game's clip when it cuts the text
            boolean clipped = o.cx0 > o.x - 3 || o.cy0 > o.y - 3 || o.cx1 < o.x + tw + 3 || o.cy1 < o.y + f.getHeight() + 3;
            if (clipped) {
                int cx0 = (int) Math.floor(ox + o.cx0 * k), cx1 = (int) Math.ceil(ox + o.cx1 * k);
                int cy0 = (int) Math.floor(oy + dh - o.cy1 * k), cy1 = (int) Math.ceil(oy + dh - o.cy0 * k);
                if (!scissor || cx0 != sx0 || cy0 != sy0 || cx1 != sx1 || cy1 != sy1) {
                    batch.flush();
                    Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                    Gdx.gl.glScissor(cx0, cy0, Math.max(0, cx1 - cx0), Math.max(0, cy1 - cy0));
                    scissor = true;
                    sx0 = cx0; sy0 = cy0; sx1 = cx1; sy1 = cy1;
                }
            } else if (scissor) {
                batch.flush();
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                scissor = false;
            }
            float px = f.nominalPx() * k;
            int ascPx = Math.round(f.getBaselinePosition() * k);
            int pad = (int) Math.ceil(px * 0.45f) + 1;
            int cellH = (int) Math.ceil(f.getHeight() * k) + 2 * pad + 1;
            batch.setColor((o.rgb >> 16 & 255) / 255f, (o.rgb >> 8 & 255) / 255f, (o.rgb & 255) / 255f, 1f);
            float baseGL = Math.round(oy + dh - o.y * k - ascPx);
            float bottom = baseGL - (cellH - pad - ascPx);
            String s = o.text;
            float[] nat = natural(f, s, px);
            float target = tw * k;   // the game's width of the string, in window pixels
            float factor = nat[s.length()] > 0 ? Math.max(0.9f, Math.min(1.1f, target / nat[s.length()])) : 1f;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int pen = o.x + (int) (nat[i] / k);   // canvas x, only for culling
                if (c > ' ' && pen + 24 > o.cx0 && pen - 24 < o.cx1) {
                    Glyph gl = glyph(f, c, px, ascPx, cellH, pad);
                    float gx = Math.round(ox + o.x * k + nat[i] * factor) - gl.pad;
                    batch.draw(gl.region, gx, bottom, gl.region.getRegionWidth(), gl.region.getRegionHeight());
                }
            }
            if (f.isUnderlined()) {
                batch.draw(white(), ox + o.x * k, oy + dh - (o.y + f.getBaselinePosition() + 2) * k, tw * k, Math.max(1f, k));
            }
        }
        batch.setColor(1, 1, 1, 1);
        batch.end();
        if (scissor) Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    void dispose() {
        clear();
        if (white != null) white.dispose();
    }
}
