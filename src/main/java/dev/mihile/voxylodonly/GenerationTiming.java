package dev.mihile.voxylodonly;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/** Optional phase timing. Disabled by default; benchmark runs opt in explicitly. */
final class GenerationTiming {
    private static final boolean ENABLED = Boolean.getBoolean("voxy.lod.profile");
    private static final ThreadMXBean CPU = ENABLED ? ManagementFactory.getThreadMXBean() : null;
    private final int x, z;
    static boolean enabled() { return ENABLED; }
    GenerationTiming(int x, int z) { this.x=x; this.z=z; }
    Scope phase(String name, int chunks) { return ENABLED ? new Scope(name,chunks) : Scope.OFF; }
    private static long cpu() { return CPU != null && CPU.isThreadCpuTimeEnabled() ? CPU.getCurrentThreadCpuTime() : 0; }

    final class Scope implements AutoCloseable {
        private static final Scope OFF = new GenerationTiming(0,0).new Scope();
        private final String name;
        private final int chunks;
        private final long start, cpuStart;
        private final Thread owner = Thread.currentThread();
        private final AtomicLong workerCpu = new AtomicLong();
        private Scope() { name=null; chunks=0; start=cpuStart=0; }
        private Scope(String name,int chunks) { this.name=name;this.chunks=chunks;start=System.nanoTime();cpuStart=cpu(); }
        long startWorker() { return name == null ? 0 : cpu(); }
        void endWorker(long started) {
            if (name != null && Thread.currentThread() != owner) workerCpu.addAndGet(cpu()-started);
        }
        @Override public void close() {
            if (name != null) VoxyLodOnly.LOGGER.info("LOD_PHASE x={} z={} stage={} chunks={} wallMs={} cpuMs={}",
                    x,z,name,chunks,(System.nanoTime()-start)/1_000_000.0,(cpu()-cpuStart+workerCpu.get())/1_000_000.0);
        }
    }
}
