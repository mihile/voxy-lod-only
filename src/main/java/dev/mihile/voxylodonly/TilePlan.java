package dev.mihile.voxylodonly;

import java.util.*;

/** Bounded near-first tile traversal; floor division is essential west/south of zero. */
public final class TilePlan implements Iterator<TilePlan.Tile> {
    public record Tile(int x, int z) {}
    private final Deque<Tile> tiles = new ArrayDeque<>();
    private final int size, originX, originZ;
    private final int minX, maxX, minZ, maxZ;
    private final int minTX, maxTX, minTZ, maxTZ, centerTX, centerTZ, maxRing;
    private final int innerRadius;
    private int vx, vz;
    private int directionSector;
    private int directionReorders;
    private final GenerationArea area;
    private int ring;
    private boolean eagerFilled;
    public final int centerX, centerZ, radius;
    public TilePlan(int x, int z, int radius) {
        this(x, z, radius, InMemoryGenerator.TILE);
    }
    public TilePlan(int x, int z, int radius, int size) {
        this(x,z,radius,size,false);
    }
    public TilePlan(int x, int z, int radius, int size, boolean requestOrigin) {
        this(x,z,radius,size,requestOrigin,0,0,0,null);
    }
    public TilePlan(int x, int z, int radius, int size, boolean requestOrigin, int innerRadius, int vx, int vz) {
        this(x,z,radius,size,requestOrigin,Math.max(0,innerRadius),vx,vz,null);
    }
    public TilePlan(GenerationArea area, int size, boolean requestOrigin) {
        this(Math.floorDiv(area.centerX(),16),Math.floorDiv(area.centerZ(),16),0,size,requestOrigin,0,0,0,area);
    }
    private TilePlan(int x, int z, int radius, int size, boolean requestOrigin, int innerRadius, int vx, int vz, GenerationArea area) {
        if (radius < 0 || radius > 128 || Math.abs((long)x) > 1_875_000 || Math.abs((long)z) > 1_875_000)
            throw new IllegalArgumentException("Generation coordinates/radius outside limits");
        if (size != 4 && size != 8 && size != 16 && size != 32) throw new IllegalArgumentException("Unsupported tile size");
        centerX = x; centerZ = z; this.radius = radius;
        this.innerRadius = innerRadius; this.vx = vx; this.vz = vz;
        this.directionSector = sectorOf(vx, vz);
        this.area=area;
        this.minX=area==null?x-radius:area.minX(); this.maxX=area==null?x+radius:area.maxX();
        this.minZ=area==null?z-radius:area.minZ(); this.maxZ=area==null?z+radius:area.maxZ();
        this.size=size; originX=requestOrigin?this.minX:0; originZ=requestOrigin?this.minZ:0;
        minTX=Math.floorDiv(this.minX-originX,size); maxTX=Math.floorDiv(this.maxX-originX,size);
        minTZ=Math.floorDiv(this.minZ-originZ,size); maxTZ=Math.floorDiv(this.maxZ-originZ,size);
        centerTX=Math.floorDiv(x-originX,size); centerTZ=Math.floorDiv(z-originZ,size);
        maxRing=Math.max(Math.max(centerTX-minTX,maxTX-centerTX),Math.max(centerTZ-minTZ,maxTZ-centerTZ));
    }
    static int sectorOf(int vx, int vz) {
        if (vx == 0 && vz == 0) return -1;
        double angle = Math.atan2(vz, vx);
        int sector = (int)Math.floor((angle + Math.PI / 8.0) / (Math.PI / 4.0));
        return Math.floorMod(sector, 8);
    }
    private static long distance(Tile tile, int x, int z, int size) {
        long dx = (long)tile.x * size + size / 2 - x;
        long dz = (long)tile.z * size + size / 2 - z;
        return dx * dx + dz * dz;
    }
    private int tier(Tile tile) {
        int cMinX = Math.max(startX(tile), minX), cMaxX = Math.min(startX(tile) + size - 1, maxX);
        int cMinZ = Math.max(startZ(tile), minZ), cMaxZ = Math.min(startZ(tile) + size - 1, maxZ);
        boolean insideInner = innerRadius > 0
                && cMinX >= centerX - innerRadius && cMaxX <= centerX + innerRadius
                && cMinZ >= centerZ - innerRadius && cMaxZ <= centerZ + innerRadius;
        if (insideInner) return 3;
        boolean overlapsInner = innerRadius > 0
                && cMinX <= centerX + innerRadius && cMaxX >= centerX - innerRadius
                && cMinZ <= centerZ + innerRadius && cMaxZ >= centerZ - innerRadius;
        if (overlapsInner) return 2;
        boolean fullTile = (cMaxX - cMinX + 1) == size && (cMaxZ - cMinZ + 1) == size;
        return fullTile ? 0 : 1;
    }
    private int hemisphere(Tile tile) {
        if (vx == 0 && vz == 0) return 0;
        int cMinX = Math.max(startX(tile), minX), cMaxX = Math.min(startX(tile) + size - 1, maxX);
        int cMinZ = Math.max(startZ(tile), minZ), cMaxZ = Math.min(startZ(tile) + size - 1, maxZ);
        double dx2 = (double)cMinX + cMaxX - 2.0 * centerX;
        double dz2 = (double)cMinZ + cMaxZ - 2.0 * centerZ;
        double dot = dx2 * vx + dz2 * vz;
        if (dot > 0) {
            double distSq = dx2 * dx2 + dz2 * dz2;
            double vSq = (double)vx * vx + (double)vz * vz;
            return 2.0 * dot * dot >= distSq * vSq ? 0 : 1;
        }
        return dot == 0 ? 2 : 3;
    }
    private long centerDistanceSq(Tile tile) {
        int cMinX = Math.max(startX(tile), minX), cMaxX = Math.min(startX(tile) + size - 1, maxX);
        int cMinZ = Math.max(startZ(tile), minZ), cMaxZ = Math.min(startZ(tile) + size - 1, maxZ);
        long dx2 = (long)cMinX + cMaxX - 2L * centerX;
        long dz2 = (long)cMinZ + cMaxZ - 2L * centerZ;
        return dx2 * dx2 + dz2 * dz2;
    }
    private Comparator<Tile> prioritizedOrder() {
        return Comparator.comparingInt(this::tier)
                .thenComparingInt(this::hemisphere)
                .thenComparingLong(this::centerDistanceSq);
    }
    public void setDirection(int newVx, int newVz) {
        int newSector = sectorOf(newVx, newVz);
        if (newSector < 0 || newSector == this.directionSector) return;
        this.vx = newVx; this.vz = newVz;
        this.directionSector = newSector;
        if (!eagerFilled) {
            if (ring == 0) {
                fill();
                return;
            }
            while (ring <= maxRing) fillNextRing();
            eagerFilled = true;
        }
        var remaining = new ArrayList<>(tiles);
        remaining.sort(prioritizedOrder());
        tiles.clear();
        tiles.addAll(remaining);
        directionReorders++;
    }
    int directionSector() { return directionSector; }
    int directionReorders() { return directionReorders; }
    public boolean includes(int x, int z) { return area==null ? Math.abs((long)x - centerX) <= radius && Math.abs((long)z - centerZ) <= radius : area.includes(x,z); }
    public long totalChunks() { return area==null ? (2L*radius+1)*(2L*radius+1) : area.totalChunks(); }
    GenerationArea blockArea() { return area; }
    public String description() { return area==null ? "centerChunks="+centerX+","+centerZ+", shape=square, radiusChunks="+radius : area.description(); }
    public int startX(Tile tile) { return Math.addExact(originX,Math.multiplyExact(tile.x,size)); }
    public int startZ(Tile tile) { return Math.addExact(originZ,Math.multiplyExact(tile.z,size)); }
    private void add(List<Tile> ordered,int x,int z) {
        if (x>=minTX && x<=maxTX && z>=minTZ && z<=maxTZ) ordered.add(new Tile(x,z));
    }
    private void fillNextRing() {
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
    private void fill() {
        if (innerRadius > 0 || vx != 0 || vz != 0) {
            if (!eagerFilled && ring == 0) {
                eagerFilled = true;
                List<Tile> all = new ArrayList<>((maxTX - minTX + 1) * (maxTZ - minTZ + 1));
                for (int z = minTZ; z <= maxTZ; z++) for (int x = minTX; x <= maxTX; x++) all.add(new Tile(x, z));
                all.sort(prioritizedOrder());
                tiles.addAll(all);
                ring = maxRing + 1;
            }
            return;
        }
        while (tiles.isEmpty() && ring<=maxRing) fillNextRing();
    }
    @Override public boolean hasNext() { fill(); return !tiles.isEmpty(); }
    public Tile peek() { if (!hasNext()) throw new NoSuchElementException(); return tiles.peekFirst(); }
    @Override public Tile next() { if (!hasNext()) throw new NoSuchElementException(); return tiles.removeFirst(); }
}
