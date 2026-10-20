package dev.mihile.voxylodonly;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.voxelization.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only bridge to the package-private bounded conversion pipeline. */
public final class ConversionVerification {
    public static void run(ServerLevel level, WorldEngine world) throws Exception {
        var eligible=new HashSet<Long>();
        for(int z=128;z<132;z++) for(int x=128;x<132;x++) eligible.add(ChunkPos.asLong(x,z));
        var batch=new InMemoryGenerator(level,()->false).generate(32,32,eligible,true);
        var field=WorldEngine.class.getDeclaredField("refCount");field.setAccessible(true);
        var refs=(AtomicInteger)field.get(world);
        int initial=refs.get();
        var queued=new ArrayDeque<Runnable>();
        var cancelled=new PreparedLodBatch(world,batch,()->false,queued::addLast);
        cancelled.fill();
        if(queued.size()!=PreparedLodBatch.MAX_RETAINED_RESULTS || cancelled.reservedCount()!=PreparedLodBatch.MAX_RETAINED_RESULTS)
            throw new IllegalStateException("Unbounded conversion result reservation");
        cancelled.skip();
        if(cancelled.reservedCount()!=PreparedLodBatch.MAX_RETAINED_RESULTS)
            throw new IllegalStateException("Skipped conversion did not replenish reserved slot");
        cancelled.close();
        if(cancelled.drained() || refs.get()!=initial+1) throw new IllegalStateException("Conversion lease released before workers drained");
        while(!queued.isEmpty()) queued.removeFirst().run();
        if(!cancelled.drained() || refs.get()!=initial) throw new IllegalStateException("Conversion cancellation leaked reference");
        var rejected=new PreparedLodBatch(world,batch,()->false,r -> {throw new RejectedExecutionException("test");});
        try { rejected.fill();throw new IllegalStateException("Rejected executor accepted conversion"); }
        catch(RejectedExecutionException expected) { rejected.close(); }
        if(!rejected.drained() || refs.get()!=initial) throw new IllegalStateException("Rejected conversion leaked reference");

        // Reference uses the pre-pipeline conversion on one thread and the same mapper.
        // Comparing full packed arrays catches cross-thread palette/light/mip corruption.
        var reference=new ArrayList<VoxelizedSection[]>();
        for(var chunk:batch.output()) {
            var sections=new VoxelizedSection[chunk.getSections().length];
            for(int i=0;i<sections.length;i++) {
                var source=chunk.getSection(i);int y=chunk.getMinSection()+i;
                var pos=SectionPos.of(chunk.getPos(),y);
                var sky=batch.light().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                var block=batch.light().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                sections[i]=WorldConversionFactory.convert(VoxelizedSection.createEmpty().setPosition(chunk.getPos().x,y,chunk.getPos().z),
                        world.getMapper(),source.getStates(),source.getBiomes(),(x,ly,z)->(byte)((sky==null?0:sky.get(x,ly,z))|((block==null?0:block.get(x,ly,z))<<4)));
                WorldVoxilizedSectionMipper.mipSection(sections[i],world.getMapper());
            }
            reference.add(sections);
        }
        var pool=Executors.newFixedThreadPool(3);
        var parallel=new PreparedLodBatch(world,batch,()->false,pool);
        try {
            parallel.fill();
            for(int c=0;c<reference.size();c++) {
                var prepared=parallel.peek();
                var actual=prepared.sections().get(30,TimeUnit.SECONDS);var expected=reference.get(c);
                if(prepared.index()!=c || actual.length!=expected.length) throw new IllegalStateException("Conversion order mismatch");
                for(int s=0;s<actual.length;s++) {
                    var a=actual[s];var e=expected[s];
                    if(a.x!=e.x || a.y!=e.y || a.z!=e.z || a.lvl0NonAirCount!=e.lvl0NonAirCount || !Arrays.equals(a.section,e.section))
                        throw new IllegalStateException("Packed LOD conversion mismatch at "+c+","+s);
                }
                parallel.advance();
            }
        } finally { parallel.close();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("Conversion did not drain"); }
        // Native ingestion can release its own lease while this longer comparison runs.
        // drained becomes true only after our releaseRef has returned; exact deltas above
        // are checked with the manually queued, synchronously completed lease cases.
        if(!parallel.drained()) throw new IllegalStateException("Completed conversion lease did not drain");
    }
}
