package javax.microedition.lcdui;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** MIDP Image backed by an int[] of ARGB pixels (row-major, no stride). */
public class Image {
    final int width, height;
    final int[] pixels;
    final boolean mutable;

    Image(int w, int h, int[] px, boolean mutable) {
        this.width = w;
        this.height = h;
        this.pixels = px;
        this.mutable = mutable;
    }

    public static Image createImage(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException();
        int[] px = new int[width * height];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        return new Image(width, height, px, true);
    }

    public static Image createImage(String name) throws IOException {
        String n = name.startsWith("/") ? name.substring(1) : name;
        ClassLoader cl = gothic3.platform.Platform.resourceLoader();
        try (InputStream in = cl.getResourceAsStream(n)) {
            if (in == null) throw new IOException("Resource not found: " + name);
            return createImage(in);
        }
    }

    public static Image createImage(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        byte[] b = bo.toByteArray();
        return createImage(b, 0, b.length);
    }

    public static Image createImage(byte[] data, int off, int len) {
        if (data == null) throw new NullPointerException();
        if (off < 0 || len < 0 || off + len > data.length) throw new ArrayIndexOutOfBoundsException();
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data, off, len));
            if (bi == null) throw new IllegalArgumentException("Unsupported image format");
            return fromBuffered(bi);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    public static Image createImage(Image src) {
        return new Image(src.width, src.height, src.pixels.clone(), false);
    }

    public static Image createImage(Image src, int x, int y, int w, int h, int transform) {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > src.width || y + h > src.height)
            throw new IllegalArgumentException();
        boolean swap = transform >= 4;
        int tw = swap ? h : w, th = swap ? w : h;
        int[] out = new int[tw * th];
        for (int dy = 0; dy < th; dy++)
            for (int dx = 0; dx < tw; dx++) {
                int[] s = Graphics.srcCoord(transform, dx, dy, w, h);
                out[dy * tw + dx] = src.pixels[(y + s[1]) * src.width + x + s[0]];
            }
        return new Image(tw, th, out, false);
    }

    public static Image createRGBImage(int[] rgb, int w, int h, boolean processAlpha) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException();
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = processAlpha ? rgb[i] : (rgb[i] | 0xFF000000);
        return new Image(w, h, px, false);
    }

    static Image fromBuffered(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = new int[w * h];
        if (bi.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY) {
            // BufferedImage.getRGB would apply a linear-gray -> sRGB conversion; keep raw values.
            Raster r = bi.getRaster();
            int bands = r.getNumBands();
            int bits = bi.getColorModel().getComponentSize(0);
            int max = (1 << bits) - 1;
            int[] p = new int[bands];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    r.getPixel(x, y, p);
                    int g = p[0] * 255 / max;
                    int a = bands > 1 ? p[1] * 255 / max : 255;
                    px[y * w + x] = (a << 24) | (g << 16) | (g << 8) | g;
                }
        } else {
            bi.getRGB(0, 0, w, h, px, 0, w);
        }
        return new Image(w, h, px, false);
    }

    public Graphics getGraphics() {
        if (!mutable) throw new IllegalStateException("Image is immutable");
        return new Graphics(pixels, width, height);
    }

    public boolean isMutable() { return mutable; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void getRGB(int[] rgbData, int offset, int scanlength, int x, int y, int w, int h) {
        if (rgbData == null) throw new NullPointerException();
        if (w < 0 || h < 0 || x < 0 || y < 0 || x + w > width || y + h > height)
            throw new IllegalArgumentException();
        if (w == 0 || h == 0) return;
        for (int j = 0; j < h; j++)
            System.arraycopy(pixels, (y + j) * width + x, rgbData, offset + j * scanlength, w);
    }

    /** Non-MIDP: raw ARGB access for the platform layer. */
    public int[] getPixelsInternal() { return pixels; }
}
