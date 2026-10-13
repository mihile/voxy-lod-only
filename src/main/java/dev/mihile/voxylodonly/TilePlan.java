package dev.mihile.voxylodonly;

import java.util.*;

/** Bounded near-first tile traversal; floor division is essential west/south of zero. */
public final class TilePlan implements Iterator<TilePlan.Tile> {
    public record Tile(int x, int z) {}
    private final Deque<Tile> tiles = new ArrayDeque<>();
    private final int size, originX, originZ;
    private final int minTX, maxTX, minTZ, maxTZ, centerTX, centerTZ, maxRing;
    private final GenerationArea area;
    private int ring;
    public final int centerX, centerZ, radius;
    public TilePlan(int x, int z, int radius) {
        this(x, z, radius, InMemoryGenerator.TILE);
    }
    public TilePlan(int x, int z, int radius, int size) {
        this(x,z,radius,size,false);
    }
    public TilePlan(int x, int z, int radius, int size, boolean requestOrigin) {
        this(x,z,radius,size,requestOrigin,null);
    }
    public TilePlan(GenerationArea area, int size, boolean requestOrigin) {
        this(Math.floorDiv(area.centerX(),16),Math.floorDiv(area.centerZ(),16),0,size,requestOrigin,area);
    }
    private TilePlan(int x, int z, int radius, int size, boolean requestOrigin, GenerationArea area) {
        if (radius < 0 || radius > 128 || Math.abs((long)x) > 1_875_000 || Math.abs((long)z) > 1_875_000)
            throw new IllegalArgumentException("Generation coordinates/radius outside limits");
        if (size != 4 && size != 8 && size != 16 && size != 32) throw new IllegalArgumentException("Unsupported tile size");
        centerX = x; centerZ = z; this.radius = radius;
        this.area=area;
        int minX=area==null?x-radius:area.minX(), maxX=area==null?x+radius:area.maxX();
        int minZ=area==null?z-radius:area.minZ(), maxZ=area==null?z+radius:area.maxZ();
        this.size=size; originX=requestOrigin?minX:0; originZ=requestOrigin?minZ:0;
        minTX=Math.floorDiv(minX-originX,size); maxTX=Math.floorDiv(maxX-originX,size);
        minTZ=Math.floorDiv(minZ-originZ,size); maxTZ=Math.floorDiv(maxZ-originZ,size);
        centerTX=Math.floorDiv(x-originX,size); centerTZ=Math.floorDiv(z-originZ,size);
        maxRing=Math.max(Math.max(centerTX-minTX,maxTX-centerTX),Math.max(centerTZ-minTZ,maxTZ-centerTZ));
    }
    private static long distance(Tile tile, int x, int z, int size) {
        long dx = (long)tile.x * size + size / 2 - x;
        long dz = (long)tile.z * size + size / 2 - z;
        return dx * dx + dz * dz;
    }
    public boolean includes(int x, int z) { return area==null ? Math.abs((long)x - centerX) <= radius && Math.abs((long)z - centerZ) <= radius : area.includes(x,z); }
    public long totalChunks() { return area==null ? (2L*radius+1)*(2L*radius+1) : area.totalChunks(); }
    GenerationArea blockArea() { return area; }
    public String description() { return area==null ? "centerChunks="+centerX+","+centerZ+", shape=square, radiusChunks="+radius : area.description(); }
    public int startX(Tile tile) { return Math.addExact(originX,Math.multiplyExact(tile.x,size)); }
    public int startZ(Tile tile) { return Math.addExact(originZ,Math.multiplyExact(tile.z,size)); }
    private void add(List<Tile> ordered,int x,int z) {
        if (x>=minTX && x<=maxTX && z>=minTZ && z<=maxTZ) ordered.add(new Tile(x,z));
    }
    private void fill() {
        while (tiles.isEmpty() && ring<=maxRing) {
            int r=ring++; List<Tile> ordered=new ArrayList<>();
            if (r==0) add(ordered,centerTX,centerTZ);
            else {
                for (int x=centerTX-r;x<=centerTX+r;x++) {
                    add(ordered,x,centerTZ-r); add(ordered,x,centerTZ+r);
                }
                for (int z=centerTZ-r+1;z<centerTZ+r;z++) {
                    add(ordered,centerTX-r,z); add(ordered,centerTX+r,z);
                }
            }
            ordered.sort(Comparator.comparingLong(tile -> distance(tile,centerX-originX,centerZ-originZ,size)));
            tiles.addAll(ordered);
        }
    }
    @Override public boolean hasNext() { fill(); return !tiles.isEmpty(); }
    @Override public Tile next() { if (!hasNext()) throw new NoSuchElementException(); return tiles.removeFirst(); }
}
