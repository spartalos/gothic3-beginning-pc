package gothic3.platform.scene3d;

import javax.microedition.lcdui.Image;

/**
 * Immutable copy of the tile map of the current area, as the 3D renderer needs it (the game thread builds it,
 * the render thread reads it). A new instance is published whenever the tile grid, the tilesets or the palette
 * change; frames of the same map share one instance, so {@code ==} tells the renderer whether to rebuild geometry.
 *
 * <p>{@code cells[y * w + x]} is the game's packed cell, after the game's overlap-link pass:
 * layer 0: bits 0-7 tile (signed byte, negative = none), bits 8-10 transform, bit 15 walkable;
 * layer 1: bits 16-23 tile, bits 24-26 transform; bits 12-14 = offset (rows up) of the first layer-1 tile
 * of the column whose base row is this row (7 = none), bits 28-30 = offset of the next one in the chain (7 = end).
 */
public final class MapSnapshot {
    public final int version;
    public final int w, h;
    public final int tileW, tileH;
    public final int[] cells;
    /** Tileset sheets (layer 0 / layer 1), already resolved for the palette theme. May be null if not loaded. */
    public final Image sheet0, sheet1;
    public final int cols0, rows0, cols1, rows1;
    /** Game tileset ids (identify the art set for per-tileset tile table entries). */
    public final int tileset0, tileset1;
    /** Game map (area) id, -1 if unknown. Used by per-map tile table overrides and the tile editor. */
    public final int mapId;

    public MapSnapshot(int version, int w, int h, int tileW, int tileH, int[] cells, Image sheet0, Image sheet1,
                       int cols0, int rows0, int cols1, int rows1, int tileset0, int tileset1) {
        this(version, w, h, tileW, tileH, cells, sheet0, sheet1, cols0, rows0, cols1, rows1, tileset0, tileset1, -1);
    }

    public MapSnapshot(int version, int w, int h, int tileW, int tileH, int[] cells, Image sheet0, Image sheet1,
                       int cols0, int rows0, int cols1, int rows1, int tileset0, int tileset1, int mapId) {
        this.mapId = mapId;
        this.version = version;
        this.w = w;
        this.h = h;
        this.tileW = tileW;
        this.tileH = tileH;
        this.cells = cells;
        this.sheet0 = sheet0;
        this.sheet1 = sheet1;
        this.cols0 = Math.max(1, cols0);
        this.rows0 = Math.max(1, rows0);
        this.cols1 = Math.max(1, cols1);
        this.rows1 = Math.max(1, rows1);
        this.tileset0 = tileset0;
        this.tileset1 = tileset1;
    }
}
