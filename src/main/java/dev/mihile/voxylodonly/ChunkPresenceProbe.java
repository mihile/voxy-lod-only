package dev.mihile.voxylodonly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Bounded async chunk existence probe.
 * Converts each read result to a tri-state (ABSENT / PRESENT / ERROR) immediately upon completion
 * so deserialized CompoundTag instances are never retained while waiting for slower reads in the batch.
 * On cancellation, stops submitting new reads and waits asynchronously for all in-flight reads to drain
 * before completing the returned future, preventing overlapping disk reads across batches.
 */
final class ChunkPresenceProbe {
    static final int DEFAULT_MAX_CONCURRENCY = 16;

    enum State {
        ABSENT,
        PRESENT,
        ERROR
    }

    record Result(Set<Long> absentPositions, int presentCount, int errorCount) {}

    private ChunkPresenceProbe() {}

    static CompletableFuture<Result> probe(
            List<Long> knownAbsent,
            List<ChunkPos> toRead,
            Function<ChunkPos, CompletableFuture<Optional<CompoundTag>>> reader,
            int maxConcurrency,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        int totalToRead = toRead.size();
        if (totalToRead == 0) {
            Set<Long> eligible = new HashSet<>(knownAbsent);
            return CompletableFuture.completedFuture(new Result(eligible, 0, 0));
        }
        int limit = Math.max(1, Math.min(maxConcurrency, totalToRead));
        State[] states = new State[totalToRead];
        AtomicInteger nextIndex = new AtomicInteger(0);
        AtomicInteger activeLanes = new AtomicInteger(limit);
        AtomicBoolean stopping = new AtomicBoolean(false);
        CompletableFuture<Result> completion = new CompletableFuture<>();

        Runnable pump = new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (stopping.get() || cancelled.getAsBoolean()) {
                        stopping.set(true);
                        finishLane();
                        return;
                    }
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= totalToRead) {
                        finishLane();
                        return;
                    }
                    ChunkPos pos = toRead.get(idx);
                    CompletableFuture<Optional<CompoundTag>> readFuture;
                    try {
                        readFuture = reader.apply(pos);
                    } catch (Throwable t) {
                        states[idx] = State.ERROR;
                        continue;
                    }
                    if (readFuture == null) {
                        states[idx] = State.ERROR;
                        continue;
                    }
                    if (readFuture.isDone()) {
                        states[idx] = extractState(readFuture);
                        continue;
                    }
                    readFuture.handle((opt, err) -> {
                        states[idx] = (err != null || opt == null)
                                ? State.ERROR
                                : (opt.isEmpty() ? State.ABSENT : State.PRESENT);
                        if (cancelled.getAsBoolean()) {
                            stopping.set(true);
                        }
                        this.run();
                        return null;
                    });
                    return;
                }
            }

            private State extractState(CompletableFuture<Optional<CompoundTag>> doneFuture) {
                try {
                    Optional<CompoundTag> opt = doneFuture.join();
                    return opt == null ? State.ERROR : (opt.isEmpty() ? State.ABSENT : State.PRESENT);
                } catch (Throwable t) {
                    return State.ERROR;
                }
            }

            private void finishLane() {
                if (activeLanes.decrementAndGet() == 0) {
                    if (stopping.get() || cancelled.getAsBoolean()) {
                        completion.completeExceptionally(new CancellationException());
                        return;
                    }
                    Set<Long> eligible = new HashSet<>(knownAbsent.size() + totalToRead);
                    eligible.addAll(knownAbsent);
                    int present = 0;
                    int errors = 0;
                    for (int i = 0; i < totalToRead; i++) {
                        State s = states[i];
                        if (s == State.ABSENT) {
                            eligible.add(toRead.get(i).toLong());
                        } else if (s == State.PRESENT) {
                            present++;
                        } else {
                            errors++;
                        }
                    }
                    completion.complete(new Result(eligible, present, errors));
                }
            }
        };

        for (int i = 0; i < limit; i++) {
            pump.run();
        }
        return completion;
    }
}
