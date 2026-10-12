package dev.mihile.voxylodonly;

import java.util.*;

/** Bounded near-first tile traversal; floor division is essential west/south of zero. */
public final class TilePlan implements Iterator<TilePlan.Tile> {
    public record Tile(int x, int z) {}
    private final Iterator<Tile> tiles;
    public final int centerX, centerZ, radius;
    public TilePlan(int x, int z, int radius) {
        this(x, z, radius, InMemoryGenerator.TILE);
    }
    public TilePlan(int x, int z, int radius, int size) {
        if (radius < 0 || radius > 128 || Math.abs((long)x) > 1_875_000 || Math.abs((long)z) > 1_875_000)
            throw new IllegalArgumentException("Generation coordinates/radius outside limits");
        if (size != 4 && size != 8) throw new IllegalArgumentException("Unsupported tile size");
        centerX = x; centerZ = z; this.radius = radius;
        List<Tile> ordered = new ArrayList<>();
        for (int tz = Math.floorDiv(z - radius, size); tz <= Math.floorDiv(z + radius, size); tz++) {
            for (int tx = Math.floorDiv(x - radius, size); tx <= Math.floorDiv(x + radius, size); tx++) ordered.add(new Tile(tx, tz));
        }
        ordered.sort(Comparator.comparingLong(tile -> distance(tile, x, z, size)));
        tiles = ordered.iterator();
    }
    private static long distance(Tile tile, int x, int z, int size) {
        long dx = (long)tile.x * size + size / 2 - x;
        long dz = (long)tile.z * size + size / 2 - z;
        return dx * dx + dz * dz;
    }
    public boolean includes(int x, int z) { return Math.abs((long)x - centerX) <= radius && Math.abs((long)z - centerZ) <= radius; }
    @Override public boolean hasNext() { return tiles.hasNext(); }
    @Override public Tile next() { return tiles.next(); }
}
