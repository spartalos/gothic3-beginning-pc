package gothic3.desktop.render3d.autotag;

import java.util.Map;
import java.util.TreeMap;

/** Accumulated usage statistics of one tile id of one tileset in one layer, over all maps. */
final class TileStats {
    int layer, ts, id;
    int n;                       // occurrences
    int maps;                    // number of distinct maps
    int lastMap = -1;
    int fixedN;                  // occurrences in the 53 real maps (not prefabs)
    // layer 0
    int walk;                    // walkable bit set
    int withL1;                  // a layer-1 tile (any, linked or not) sits on the same cell
    int withL1Col;               // ... and that tile is part of a column of this cell (some column's chain includes it)
    int[] xform = new int[8];
    // layer 1
    int[] depth = new int[8];    // file depth tag histogram (offset to the base row)
    int top;                     // last piece of its column
    int bottom;                  // first piece of its column (offset smallest)
    int single;                  // column of exactly one piece
    int onWalkBase;              // base cell's layer 0 is walkable
    int onWalkSelf;              // own cell's layer 0 is walkable
    int onNoGround;              // own cell has no layer-0 tile
    int posSum, lenSum;          // index from the bottom, column length
    int unlinked;                // tag 7 (never drawn by the game)
    int belowSame, aboveSame;    // neighbours in the column (piece below / above is the same id)
    int upperHalf;               // distribution of "what is above": count with nothing above
    final Map<Integer, Integer> below = new TreeMap<>(), above = new TreeMap<>();
    // layer 0: what is on the four sides (ids of layer 0 are not needed) / layer 1 neighbours: ids north/south in map (not column)
    // image
    final Map<Integer, Integer> cover = new TreeMap<>(); // layer 0: (ts1 << 8 | id) of the layer-1 tile on the same cell -> count
    Features img;

    String key() { return layer + "/" + ts + "/" + id; }
}
