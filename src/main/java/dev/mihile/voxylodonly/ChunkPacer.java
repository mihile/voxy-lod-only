package dev.mihile.voxylodonly;

/** Monotonic, non-bursting admission of LOD chunk writes; no backlog after a pause. */
final class ChunkPacer {
    private int limit = -1;
    private long next;
    boolean admit(long now, int chunksPerSecond) {
        if (limit != chunksPerSecond) { limit = chunksPerSecond; next = now; }
        if (limit == 0) return true;
        if (now - next < 0) return false;
        next = now + (1_000_000_000L + limit - 1) / limit;
        return true;
    }
}
