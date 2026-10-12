package dev.mihile.voxylodonly;

/** Fails closed when a feature escapes WorldGenRegion and touches the live world. */
public final class GenerationGuard implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<net.minecraft.server.level.WorldGenRegion> REGION = new ThreadLocal<>();
    private final boolean previous;
    private final net.minecraft.server.level.WorldGenRegion previousRegion;
    private GenerationGuard(net.minecraft.server.level.WorldGenRegion region) {
        previous = ACTIVE.get(); previousRegion = REGION.get(); ACTIVE.set(true);
        if (region != null) REGION.set(region);
    }
    public static GenerationGuard enter() { return new GenerationGuard(null); }
    public static GenerationGuard enter(net.minecraft.server.level.WorldGenRegion region) { return new GenerationGuard(region); }
    public static net.minecraft.server.level.WorldGenRegion region() { return REGION.get(); }
    public static boolean active() { return ACTIVE.get(); }
    public static void reject(String operation) {
        if (active()) throw new IllegalStateException("LOD generation attempted live-world " + operation);
    }
    @Override public void close() {
        if (previous) ACTIVE.set(true); else ACTIVE.remove();
        if (previousRegion != null) REGION.set(previousRegion); else REGION.remove();
    }
}
