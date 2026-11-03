package gothic3.desktop.render3d.props;

/**
 * A prop mesh in sprite-local coordinates: x east (tiles) from the sprite's left edge, y up (tiles) from the sprite's top edge (so
 * y <= 0; LIE models: absolute height above the floor), z towards the camera relative to the anchor (feet). Quads, 4 vertices of 9 floats (pos3, colour, uv2, normal3) each.
 * The first {@link #mainQuads} quads are drawn in the main pass; all quads cast shadows (the rest is the never visible back face).
 */
final class PropModel {
    final float[] v;
    final int mainQuads, quads;
    final int type;
    /** Bounding box of the main-pass vertices (AO footprint): minX, maxX, minY, maxY, minZ, maxZ. */
    final float[] box = {1e9f, -1e9f, 1e9f, -1e9f, 1e9f, -1e9f};

    PropModel(float[] v, int mainQuads, int quads, int type) {
        this.v = v;
        this.mainQuads = mainQuads;
        this.quads = quads;
        this.type = type;
        for (int k = 0; k < mainQuads * 4 * 9 && k + 2 < v.length; k += 9) {
            box[0] = Math.min(box[0], v[k]); box[1] = Math.max(box[1], v[k]);
            box[2] = Math.min(box[2], v[k + 1]); box[3] = Math.max(box[3], v[k + 1]);
            box[4] = Math.min(box[4], v[k + 2]); box[5] = Math.max(box[5], v[k + 2]);
        }
    }
}
