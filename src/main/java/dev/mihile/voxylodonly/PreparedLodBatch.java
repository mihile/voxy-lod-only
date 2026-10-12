package dev.mihile.voxylodonly;

import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Bounded conversion only. World updates and real-chunk checks stay on the server thread. */
final class PreparedLodBatch {
    static final int WINDOW = 16;
    record Chunk(int index, CompletableFuture<VoxelizedSection[]> sections) {}
    final WorldEngine world;
    private final InMemoryGenerator.Batch batch;
    private final BooleanSupplier cancelled;
    private final Executor executor;
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final AtomicInteger tasks = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(), releaseStarted = new AtomicBoolean();
    private volatile boolean released;
    private int scheduled;

    PreparedLodBatch(WorldEngine world, InMemoryGenerator.Batch batch, BooleanSupplier cancelled, Executor executor) {
        this.world=world; this.batch=batch; this.cancelled=cancelled; this.executor=executor;
        world.acquireRef();
    }
    void fill() {
        while (!closed.get() && queue.size()<WINDOW && scheduled<batch.output().size()) {
            int index=scheduled++;
            tasks.incrementAndGet();
            try {
                var future=CompletableFuture.supplyAsync(() -> {
                    try { return convert(index); }
                    finally { tasks.decrementAndGet(); releaseIfDrained(); }
                },executor);
                queue.addLast(new Chunk(index,future));
            } catch (RuntimeException e) {
                tasks.decrementAndGet(); releaseIfDrained(); throw e;
            }
        }
    }
    Chunk peek() { return queue.getFirst(); }
    void advance() { queue.removeFirst(); fill(); }
    int taskCount() { return tasks.get(); }
    void close() { closed.set(true); releaseIfDrained(); }
    boolean drained() { return closed.get() && tasks.get()==0 && released; }
    private void releaseIfDrained() {
        if (closed.get() && tasks.get()==0 && releaseStarted.compareAndSet(false,true)) {
            world.releaseRef(); released=true;
        }
    }
    private VoxelizedSection[] convert(int index) {
        var chunk=batch.output().get(index);
        var result=new VoxelizedSection[chunk.getSections().length];
        for(int i=0;i<result.length;i++) {
            if (closed.get() || cancelled.getAsBoolean()) throw new CancellationException();
            var source=chunk.getSection(i);
            int y=chunk.getMinSection()+i;
            var pos=SectionPos.of(chunk.getPos(),y);
            var sky=batch.light().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            var block=batch.light().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
            var section=WorldConversionFactory.convert(VoxelizedSection.createEmpty().setPosition(chunk.getPos().x,y,chunk.getPos().z),
                    world.getMapper(),source.getStates(),source.getBiomes(),(x,ly,z) -> (byte)(
                            (sky==null?0:sky.get(x,ly,z)) | ((block==null?0:block.get(x,ly,z))<<4)));
            WorldVoxilizedSectionMipper.mipSection(section,world.getMapper());
            result[i]=section;
        }
        return result;
    }
}
