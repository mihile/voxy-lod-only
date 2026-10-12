package dev.mihile.voxylodonly;

import java.util.*;

/** Bounded near-first tile traversal; floor division is essential west/south of zero. */
public final class TilePlan implements Iterator<TilePlan.Tile> {
    public record Tile(int x, int z) {}
    private final Iterator<Tile> tiles;
    private final int size, originX, originZ;
    public final int centerX, centerZ, radius;
    public TilePlan(int x, int z, int radius) {
        this(x, z, radius, InMemoryGenerator.TILE);
    }
    public TilePlan(int x, int z, int radius, int size) {
        this(x,z,radius,size,false);
    }
    public TilePlan(int x, int z, int radius, int size, boolean requestOrigin) {
        if (radius < 0 || radius > 128 || Math.abs((long)x) > 1_875_000 || Math.abs((long)z) > 1_875_000)
            throw new IllegalArgumentException("Generation coordinates/radius outside limits");
        if (size != 4 && size != 8 && size != 16 && size != 32) throw new IllegalArgumentException("Unsupported tile size");
        centerX = x; centerZ = z; this.radius = radius;
        this.size=size; originX=requestOrigin?x-radius:0; originZ=requestOrigin?z-radius:0;
        List<Tile> ordered = new ArrayList<>();
        for (int tz = Math.floorDiv(z - radius-originZ, size); tz <= Math.floorDiv(z + radius-originZ, size); tz++) {
            for (int tx = Math.floorDiv(x - radius-originX, size); tx <= Math.floorDiv(x + radius-originX, size); tx++) ordered.add(new Tile(tx, tz));
        }
        ordered.sort(Comparator.comparingLong(tile -> distance(tile, x-originX, z-originZ, size)));
        tiles = ordered.iterator();
    }
    private static long distance(Tile tile, int x, int z, int size) {
        long dx = (long)tile.x * size + size / 2 - x;
        long dz = (long)tile.z * size + size / 2 - z;
        return dx * dx + dz * dz;
    }
    public boolean includes(int x, int z) { return Math.abs((long)x - centerX) <= radius && Math.abs((long)z - centerZ) <= radius; }
    public int startX(Tile tile) { return Math.addExact(originX,Math.multiplyExact(tile.x,size)); }
    public int startZ(Tile tile) { return Math.addExact(originZ,Math.multiplyExact(tile.z,size)); }
    @Override public boolean hasNext() { return tiles.hasNext(); }
    @Override public Tile next() { return tiles.next(); }
}
