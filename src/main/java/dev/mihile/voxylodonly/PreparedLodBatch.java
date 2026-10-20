package dev.mihile.voxylodonly;

import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Bounded conversion only. World updates and real-chunk checks stay on the server thread. */
final class PreparedLodBatch {
    static final int WINDOW = 16;
    static final int MAX_RETAINED_RESULTS = 8;
    static final int VOXELIZED_SECTION_LONGS = 4681;
    static final int SECTION_OBJECT_OVERHEAD_BYTES = 64;
    static final long ESTIMATED_SECTION_BYTES =
            (long) VOXELIZED_SECTION_LONGS * Long.BYTES + SECTION_OBJECT_OVERHEAD_BYTES;
    static final long MAX_RESERVED_RESULT_BYTES = 8L * 24L * ESTIMATED_SECTION_BYTES;
    private static final int STATE_IN_FLIGHT = 0, STATE_RETAINED = 1, STATE_RELEASED = 2;

    record Chunk(
            int index,
            ChunkPos pos,
            CompletableFuture<VoxelizedSection[]> sections,
            AtomicBoolean itemCancelled,
            AtomicInteger state,
            long byteCost) {}

    final WorldEngine world;
    private final Runnable onRelease;
    private final List<ProtoChunk> chunks;
    private final List<InMemoryGenerator.ChunkLightSnapshot> chunkLights;
    private final int totalChunks;
    private final int simulatedSections;
    private volatile LevelLightEngine light;
    private final BooleanSupplier cancelled;
    private final Executor executor;
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final AtomicInteger tasks = new AtomicInteger(), reservedResults = new AtomicInteger(),
            retainedResults = new AtomicInteger(), convertedCount = new AtomicInteger();
    private final AtomicLong reservedResultBytes = new AtomicLong(), retainedResultBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(), releaseStarted = new AtomicBoolean();
    private volatile boolean released;
    private boolean releaseConsumed;
    private int scheduled;
    private Runnable checkpoint = () -> {};

    PreparedLodBatch(WorldEngine world, InMemoryGenerator.Batch batch, BooleanSupplier cancelled, Executor executor) {
        this.world = world;
        this.onRelease = world::releaseRef;
        this.chunks = batch.output();
        this.totalChunks = chunks.size();
        this.simulatedSections = 0;
        this.light = batch.light();
        this.chunkLights = batch.lightSnapshots() != null ? new ArrayList<>(batch.lightSnapshots()) : null;
        this.cancelled = cancelled;
        this.executor = executor;
        world.acquireRef();
    }

    PreparedLodBatch(int totalChunks, int simulatedSections, Runnable onRelease, BooleanSupplier cancelled, Executor executor) {
        this.world = null;
        this.onRelease = onRelease;
        this.chunks = null;
        this.chunkLights = null;
        this.totalChunks = totalChunks;
        this.simulatedSections = simulatedSections;
        this.light = null;
        this.cancelled = cancelled;
        this.executor = executor;
    }

    PreparedLodBatch releaseConsumed() {
        this.releaseConsumed = true;
        return this;
    }

    LevelLightEngine retainedLight() {
        return light;
    }

    private long estimateChunkResultBytes(int index) {
        int secCount;
        if (chunks != null) {
            ProtoChunk c = chunks.get(index);
            secCount = c != null ? c.getSections().length : 24;
        } else {
            secCount = Math.max(1, simulatedSections);
        }
        return Math.max(ESTIMATED_SECTION_BYTES, (long) secCount * ESTIMATED_SECTION_BYTES);
    }

    void fill() {
        while (!closed.get()
                && queue.size() < WINDOW
                && tasks.get() < WINDOW
                && reservedResults.get() < MAX_RETAINED_RESULTS
                && scheduled < totalChunks) {
            long byteCost = estimateChunkResultBytes(scheduled);
            if (reservedResults.get() > 0 && reservedResultBytes.get() + byteCost > MAX_RESERVED_RESULT_BYTES) {
                break;
            }
            int index = scheduled++;
            ChunkPos pos = chunks == null ? new ChunkPos(index, 0) : chunks.get(index).getPos();
            var itemCancelled = new AtomicBoolean();
            var state = new AtomicInteger(STATE_IN_FLIGHT);
            var future = new CompletableFuture<VoxelizedSection[]>();
            tasks.incrementAndGet();
            reservedResults.incrementAndGet();
            reservedResultBytes.addAndGet(byteCost);
            try {
                executor.execute(() -> {
                    boolean transferredToRetained = false;
                    try {
                        var result = chunks == null ? convertSimulated(itemCancelled) : convert(index, itemCancelled);
                        retainedResults.incrementAndGet();
                        retainedResultBytes.addAndGet(byteCost);
                        if (!closed.get() && !itemCancelled.get() && state.compareAndSet(STATE_IN_FLIGHT, STATE_RETAINED)) {
                            transferredToRetained = true;
                            future.complete(result);
                        } else {
                            retainedResults.decrementAndGet();
                            retainedResultBytes.addAndGet(-byteCost);
                            future.completeExceptionally(new CancellationException());
                        }
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    } finally {
                        if (!transferredToRetained) {
                            state.set(STATE_RELEASED);
                            reservedResults.decrementAndGet();
                            reservedResultBytes.addAndGet(-byteCost);
                        }
                        tasks.decrementAndGet();
                        releaseIfDrained();
                    }
                });
                queue.addLast(new Chunk(index, pos, future, itemCancelled, state, byteCost));
            } catch (RuntimeException e) {
                state.set(STATE_RELEASED);
                reservedResults.decrementAndGet();
                reservedResultBytes.addAndGet(-byteCost);
                tasks.decrementAndGet();
                releaseIfDrained();
                throw e;
            }
        }
    }

    Chunk peek() {
        fill();
        return queue.peekFirst();
    }

    void advance() {
        releaseHead();
        fill();
    }

    void skip() {
        releaseHead();
        fill();
    }

    private void releaseHead() {
        var head = queue.pollFirst();
        if (head != null) releaseChunk(head);
    }

    private void releaseChunk(Chunk item) {
        item.itemCancelled().set(true);
        int prev = item.state().getAndSet(STATE_RELEASED);
        if (prev == STATE_RETAINED) {
            retainedResults.decrementAndGet();
            retainedResultBytes.addAndGet(-item.byteCost());
            reservedResults.decrementAndGet();
            reservedResultBytes.addAndGet(-item.byteCost());
        }
        if (releaseConsumed && item.index() < totalChunks) {
            if (chunks != null) chunks.set(item.index(), null);
            if (chunkLights != null) chunkLights.set(item.index(), null);
        }
    }

    int taskCount() {
        return tasks.get();
    }

    int reservedCount() {
        return reservedResults.get();
    }

    int retainedCount() {
        return retainedResults.get();
    }

    long reservedBytes() {
        return reservedResultBytes.get();
    }

    long retainedBytes() {
        return retainedResultBytes.get();
    }

    PreparedLodBatch checkpoint(Runnable checkpoint) {
        this.checkpoint = checkpoint;
        return this;
    }

    void close() {
        closed.set(true);
        while (!queue.isEmpty()) {
            releaseChunk(queue.removeFirst());
        }
        if (releaseConsumed) {
            if (chunks != null) {
                for (int i = scheduled; i < totalChunks; i++) chunks.set(i, null);
            }
            if (chunkLights != null) {
                for (int i = scheduled; i < totalChunks; i++) chunkLights.set(i, null);
            }
        }
        releaseIfDrained();
    }

    boolean drained() {
        return closed.get() && tasks.get() == 0 && released;
    }

    private void releaseIfDrained() {
        if (closed.get() && tasks.get() == 0 && releaseStarted.compareAndSet(false, true)) {
            if (releaseConsumed) {
                light = null;
                if (chunkLights != null) {
                    for (int i = 0; i < totalChunks; i++) chunkLights.set(i, null);
                }
            }
            onRelease.run();
            released = true;
        }
    }

    private VoxelizedSection[] convertSimulated(AtomicBoolean itemCancelled) {
        if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
        for (int i = 0; i < simulatedSections; i++) {
            if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
            checkpoint.run();
            if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
        }
        return null;
    }

    private VoxelizedSection[] convert(int index, AtomicBoolean itemCancelled) {
        try {
            if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
            var chunk = chunks.get(index);
            if (chunk == null) throw new CancellationException();
            var chunkLight = chunkLights != null ? chunkLights.get(index) : null;
            var currentLight = this.light;
            var result = new VoxelizedSection[chunk.getSections().length];
            for (int i = 0; i < result.length; i++) {
                if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
                checkpoint.run();
                if (closed.get() || cancelled.getAsBoolean() || itemCancelled.get()) throw new CancellationException();
                var source = chunk.getSection(i);
                int y = chunk.getMinSection() + i;
                DataLayer sky;
                DataLayer block;
                if (chunkLight != null) {
                    sky = chunkLight.sky()[i];
                    block = chunkLight.block()[i];
                } else if (currentLight != null) {
                    var pos = SectionPos.of(chunk.getPos(), y);
                    sky = currentLight.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                    block = currentLight.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                } else {
                    sky = null;
                    block = null;
                }
                var section = WorldConversionFactory.convert(
                        VoxelizedSection.createEmpty().setPosition(chunk.getPos().x, y, chunk.getPos().z),
                        world.getMapper(),
                        source.getStates(),
                        source.getBiomes(),
                        (x, ly, z) -> (byte) ((sky == null ? 0 : sky.get(x, ly, z)) | ((block == null ? 0 : block.get(x, ly, z)) << 4)));
                if (releaseConsumed && chunkLight != null) {
                    chunkLight.sky()[i] = null;
                    chunkLight.block()[i] = null;
                }
                WorldVoxilizedSectionMipper.mipSection(section, world.getMapper());
                result[i] = section;
            }
            return result;
        } finally {
            if (releaseConsumed) {
                chunks.set(index, null);
                if (chunkLights != null) chunkLights.set(index, null);
                if (convertedCount.incrementAndGet() >= totalChunks) this.light = null;
            }
        }
    }
}
