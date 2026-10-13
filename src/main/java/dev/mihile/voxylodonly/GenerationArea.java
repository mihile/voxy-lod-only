package dev.mihile.voxylodonly;

/** Block-coordinate area. Include a chunk when any integer block column intersects it. */
public record GenerationArea(int centerX, int centerZ, Shape shape, int radiusBlocks) {
    public enum Shape { circle, square }
    public static final int MAX_RADIUS = 8192;
    private static final int LIMIT = 29_999_999;
    public GenerationArea {
        if (Math.abs((long)centerX) > LIMIT || Math.abs((long)centerZ) > LIMIT
                || shape == null || radiusBlocks < 0 || radiusBlocks > MAX_RADIUS)
            throw new IllegalArgumentException("Center must be within +/-29999999; radius must be 0..8192 blocks");
    }
    public int minX() { return Math.floorDiv(Math.max(-LIMIT, centerX-radiusBlocks),16); }
    public int maxX() { return Math.floorDiv(Math.min(LIMIT, centerX+radiusBlocks),16); }
    public int minZ() { return Math.floorDiv(Math.max(-LIMIT, centerZ-radiusBlocks),16); }
    public int maxZ() { return Math.floorDiv(Math.min(LIMIT, centerZ+radiusBlocks),16); }
    private static long distance(int chunk, int center) {
        long min = (long)chunk*16, max = min+15;
        return center < min ? min-center : center > max ? center-max : 0;
    }
    public boolean includes(int x, int z) {
        if (x < minX() || x > maxX() || z < minZ() || z > maxZ()) return false;
        if (shape == Shape.square) return true;
        long dx = distance(x,centerX), dz = distance(z,centerZ);
        return dx*dx+dz*dz <= (long)radiusBlocks*radiusBlocks;
    }
    public long totalChunks() {
        if (shape == Shape.square) return (long)(maxX()-minX()+1)*(maxZ()-minZ()+1);
        long total = 0;
        for (int x = minX(); x <= maxX(); x++) {
            long dx = distance(x,centerX), remaining = (long)radiusBlocks*radiusBlocks-dx*dx;
            if (remaining < 0) continue;
            int reach = (int)Math.sqrt(remaining);
            // Keep integer geometry exact if floating-point sqrt rounds at a boundary.
            while ((long)(reach+1)*(reach+1) <= remaining) reach++;
            while ((long)reach*reach > remaining) reach--;
            int low = Math.max(minZ(),Math.floorDiv(centerZ-reach,16));
            int high = Math.min(maxZ(),Math.floorDiv(centerZ+reach,16));
            total += high-low+1;
        }
        return total;
    }
    public String description() {
        return "centerBlocks="+centerX+","+centerZ+", shape="+shape+", radiusBlocks="+radiusBlocks;
    }
}
