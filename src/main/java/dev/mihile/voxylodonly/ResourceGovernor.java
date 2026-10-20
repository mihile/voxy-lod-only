package dev.mihile.voxylodonly;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** One server-wide gate. Only owned background workers may wait here. */
final class ResourceGovernor {
    static final long BATCH_SAFETY_MARGIN_BYTES = 64L * 1024 * 1024;
    private static final long NOMINAL_STEP_CPU_NANOS = 1_000_000L;
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();

    static final class PressureAbort extends CancellationException {
        PressureAbort(String reason) { super(reason); }
    }

    record State(
            String reason,
            long heartbeat,
            long since,
            long healthySince,
            int configuredRate,
            int effectiveRate,
            long headroomBytes,
            double heapFraction,
            double oldFraction,
            boolean memoryBlocked
    ) {
        boolean paused() { return !reason.equals("running"); }
        boolean hardPressure(long now) {
            if (reason.equals("heap")) return true;
            if (!paused() || reason.equals("profile") || healthySince != 0) return false;
            long limit = reason.equals("real_chunks") && oldFraction < .76 ? 6_000_000_000L : 2_000_000_000L;
            return now - since > limit;
        }
    }

    private volatile State state = new State(
            "running", System.nanoTime(), 0L, 0L, 512, 512, Long.MAX_VALUE, 0.0, 0.0, false);
    private final AtomicLong nextStep = new AtomicLong();
    private final AtomicLong avgStepCpuNanos = new AtomicLong(NOMINAL_STEP_CPU_NANOS);
    private final AtomicLong pauses = new AtomicLong(), aborts = new AtomicLong();
    private final ThreadLocal<long[]> workerLastCpu = ThreadLocal.withInitial(() -> new long[2]);

    State snapshot() { return state; }

    void sample(long now, double heap, long headroom, double tickMillis, double tickLimit,
                int realChunkBacklog, boolean saving, boolean enabled, int rate) {
        sample(now,heap,heap,headroom,tickMillis,tickLimit,realChunkBacklog,saving,enabled,rate);
    }
    void sample(long now, double heap, double old, long headroom, double tickMillis, double tickLimit,
                int realChunkBacklog, boolean saving, boolean enabled, int rate) {
        State prev = this.state;
        boolean nextMemoryBlocked = prev.memoryBlocked();
        if (nextMemoryBlocked) {
            if (old <= .76 && headroom >= 256L * 1024 * 1024) nextMemoryBlocked = false;
        } else if (old >= .82 || headroom < 96L * 1024 * 1024) {
            nextMemoryBlocked = true;
        }
        // A healthy G1 young-generation sawtooth is not retained-heap exhaustion.
        String next = nextMemoryBlocked ? "heap" :
                headroom < 192L * 1024 * 1024 ? "allocation" :
                realChunkBacklog > 0 ? "real_chunks" : tickMillis > tickLimit ? "tick" :
                !saving ? "saving" : !enabled ? "profile" : "running";
        String finalReason = prev.reason();
        long nextSince = prev.since();
        long nextHealthySince = prev.healthySince();
        if (!next.equals("running")) {
            nextHealthySince = 0;
            if (prev.reason().equals("running")) { nextSince = now; pauses.incrementAndGet(); }
            finalReason = next;
        } else if (!prev.reason().equals("running")) {
            // Recover below a separate low watermark, then require one healthy second.
            boolean memoryPause = prev.reason().equals("heap") || prev.reason().equals("allocation");
            if (old >= .82 || headroom < (memoryPause ? 256L : 192L) * 1024 * 1024 || tickMillis > tickLimit * .8) {
                nextHealthySince = 0;
            } else if (nextHealthySince == 0) {
                nextHealthySince = now;
            } else if (now - nextHealthySince >= 1_000_000_000L) {
                finalReason = "running";
                nextSince = 0;
                nextHealthySince = 0;
                nextStep.set(now);
            }
        }
        int nextEffectiveRate = computeEffectiveRate(prev, rate, old, headroom, tickMillis, tickLimit);
        this.state = new State(
                finalReason, now, nextSince, nextHealthySince, rate, nextEffectiveRate,
                headroom, heap, old, nextMemoryBlocked);
    }

    private int computeEffectiveRate(State prev, int rate, double old, long headroom, double tickMillis, double tickLimit) {
        if (rate <= 0) return 0;
        double scale = 1.0;
        if (headroom < 384L * 1024 * 1024) {
            double hRatio = Math.max(0.0, (double)(headroom - 192L * 1024 * 1024) / (192.0 * 1024 * 1024));
            scale *= 0.35 + 0.65 * Math.min(1.0, hRatio);
        }
        if (old > 0.70) {
            scale *= Math.max(0.35, 1.0 - (old - 0.70) * 4.0);
        }
        if (tickLimit > 0 && tickMillis > tickLimit * 0.6) {
            double tRatio = (tickMillis - tickLimit * 0.6) / (tickLimit * 0.4);
            scale *= Math.max(0.35, 1.0 - Math.min(1.0, tRatio) * 0.65);
        }
        long stepCpu = avgStepCpuNanos.get();
        if (scale < 1.0 && stepCpu > NOMINAL_STEP_CPU_NANOS) {
            scale *= Math.max(0.25, (double)NOMINAL_STEP_CPU_NANOS / stepCpu);
        }
        int minRate = Math.min(rate, 16);
        int target = Math.max(minRate, Math.min(rate, (int)Math.round(rate * scale)));
        int prior = (prev.configuredRate() == rate && prev.effectiveRate() > 0) ? prev.effectiveRate() : rate;
        return Math.max(minRate, Math.min(rate, (prior * 3 + target + 2) / 4));
    }

    void recordStepCostNanos(long costNanos) {
        if (costNanos <= 0) return;
        long clamped = Math.min(20_000_000L, Math.max(50_000L, costNanos));
        avgStepCpuNanos.updateAndGet(prev -> prev <= 0 ? clamped : (prev * 7 + clamped) >>> 3);
    }

    boolean canAdmitBatch(long estimatedBatchBytes, long budgetBytes) {
        State s = this.state;
        if (s.paused() || estimatedBatchBytes > budgetBytes) return false;
        return s.headroomBytes() >= estimatedBatchBytes + BATCH_SAFETY_MARGIN_BYTES;
    }

    boolean paused() { return state.paused(); }
    boolean hardPressure(long now) { return state.hardPressure(now); }
    int effectiveStepsPerSecond() { return state.effectiveRate(); }

    void checkpoint(BooleanSupplier cancelled, boolean generation) {
        long[] last = generation ? workerLastCpu.get() : null;
        if (last != null) {
            long nowWall = System.nanoTime();
            long nowCpu = currentThreadCpuNanos(nowWall);
            if (last[0] != 0 && nowWall - last[0] < 250_000_000L) {
                recordStepCostNanos(Math.max(50_000L, nowCpu - last[1]));
            }
            last[0] = nowWall;
            last[1] = nowCpu;
        }
        long slot = 0;
        for (;;) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            long now = System.nanoTime();
            State s = this.state;
            if (s.hardPressure(now) || now - s.heartbeat() > 3_000_000_000L) throw new PressureAbort(s.reason());
            if (s.paused() || now - s.heartbeat() > 750_000_000L) {
                LockSupport.parkNanos(2_000_000L);
                continue;
            }
            int rate = s.effectiveRate();
            if (!generation || rate <= 0) return;
            if (slot == 0) {
                long spacing = Math.max(1L, 1_000_000_000L / rate);
                slot = nextStep.updateAndGet(previous -> Math.max(previous, now) + spacing);
            }
            if (now >= slot) {
                if (last != null) {
                    long exitWall = System.nanoTime();
                    last[0] = exitWall;
                    last[1] = currentThreadCpuNanos(exitWall);
                }
                return;
            }
            LockSupport.parkNanos(Math.min(2_000_000L, slot - now));
        }
    }

    private static long currentThreadCpuNanos(long fallbackWallNanos) {
        return THREAD_CPU != null && THREAD_CPU.isThreadCpuTimeSupported() && THREAD_CPU.isThreadCpuTimeEnabled()
                ? THREAD_CPU.getCurrentThreadCpuTime() : fallbackWallNanos;
    }

    void aborted() { aborts.incrementAndGet(); }
    String status() {
        State s = this.state;
        return "pressure=" + s.reason()
                + ", heapFraction=" + String.format(java.util.Locale.ROOT, "%.3f", s.heapFraction())
                + ", oldFraction=" + String.format(java.util.Locale.ROOT, "%.3f", s.oldFraction())
                + ", effectiveStepsPerSecond=" + s.effectiveRate()
                + ", avgStepCpuMicros=" + (avgStepCpuNanos.get() / 1_000L)
                + ", pressurePauses=" + pauses.get()
                + ", pressureAborts=" + aborts.get();
    }
}
