package dev.mihile.voxylodonly;

/** Fails closed when a feature escapes WorldGenRegion and touches the live world. */
public final class GenerationGuard implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private GenerationGuard() { ACTIVE.set(true); }
    public static GenerationGuard enter() { return new GenerationGuard(); }
    public static boolean active() { return ACTIVE.get(); }
    public static void reject(String operation) {
        if (active()) throw new IllegalStateException("LOD generation attempted live-world " + operation);
    }
    @Override public void close() { ACTIVE.remove(); }
}
