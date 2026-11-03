package gothic3.desktop.render3d;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import java.util.ArrayList;
import java.util.List;

/** Accumulates textured quads (pos3, packed colour, uv2) and turns them into static meshes. */
final class QuadBuilder {
    static final int FLOATS = 9;
    static final int MAX_QUADS = 16000;
    static final VertexAttributes ATTRS = new VertexAttributes(
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"));
    static final short[] QUAD_IDX = new short[MAX_QUADS * 6];
    static {
        for (int q = 0; q < MAX_QUADS; q++) {
            int i = q * 6, v = q * 4;
            QUAD_IDX[i] = (short) v;
            QUAD_IDX[i + 1] = (short) (v + 1);
            QUAD_IDX[i + 2] = (short) (v + 2);
            QUAD_IDX[i + 3] = (short) (v + 2);
            QUAD_IDX[i + 4] = (short) (v + 3);
            QUAD_IDX[i + 5] = (short) v;
        }
    }

    /** Height added to every vertex emitted from now on (raised plateaus, see MapMesh). */
    float dy;
    private float[] v = new float[FLOATS * 4 * 64];
    private int quads;
    private final List<Mesh> meshes = new ArrayList<>();
    final List<int[]> counts = new ArrayList<>(); // quads per mesh

    boolean isEmpty() { return quads == 0 && meshes.isEmpty(); }

    /** Four corners in order (any winding; both faces are drawn) with one shared unit normal (lighting). */
    void quad(float x0, float y0, float z0, float u0, float v0,
              float x1, float y1, float z1, float u1, float v1,
              float x2, float y2, float z2, float u2, float v2,
              float x3, float y3, float z3, float u3, float v3, float color, float nx, float ny, float nz) {
        quad(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, color, color, color, color, nx, ny, nz);
    }

    /** Same with one packed colour per corner (alpha fade of the map surround). */
    void quad(float x0, float y0, float z0, float u0, float v0,
              float x1, float y1, float z1, float u1, float v1,
              float x2, float y2, float z2, float u2, float v2,
              float x3, float y3, float z3, float u3, float v3, float c0, float c1, float c2, float c3, float nx, float ny, float nz) {
        if (quads == MAX_QUADS) flush();
        int o = quads * 4 * FLOATS;
        if (o + 4 * FLOATS > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
        put(o, x0, y0 + dy, z0, c0, u0, v0, nx, ny, nz);
        put(o + FLOATS, x1, y1 + dy, z1, c1, u1, v1, nx, ny, nz);
        put(o + 2 * FLOATS, x2, y2 + dy, z2, c2, u2, v2, nx, ny, nz);
        put(o + 3 * FLOATS, x3, y3 + dy, z3, c3, u3, v3, nx, ny, nz);
        quads++;
    }

    private void put(int o, float x, float y, float z, float c, float u, float w, float nx, float ny, float nz) {
        v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = c; v[o + 4] = u; v[o + 5] = w;
        v[o + 6] = nx; v[o + 7] = ny; v[o + 8] = nz;
    }

    private void flush() {
        if (quads == 0) return;
        Mesh m = new Mesh(true, quads * 4, quads * 6, ATTRS);
        m.setVertices(v, 0, quads * 4 * FLOATS);
        m.setIndices(QUAD_IDX, 0, quads * 6);
        meshes.add(m);
        counts.add(new int[] {quads * 6});
        quads = 0;
    }

    /** Finishes: returns the meshes (call once). */
    List<Mesh> build() {
        flush();
        return meshes;
    }
}
