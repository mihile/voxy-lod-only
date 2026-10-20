package dev.mihile.voxylodonly;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.Registries;
import java.security.MessageDigest;
import java.util.*;

/** Full 32 output context versus two complete 8 output contexts at opposite corners. */
public final class TileAccuracyVerification {
    public static void run(ServerLevel level) throws Exception {
        Set<Long> targets=new HashSet<>();
        for(int origin:new int[]{128,156}) for(int z=origin;z<origin+4;z++) for(int x=origin;x<origin+4;x++)
            targets.add(ChunkPos.asLong(x,z));
        var reference=hash(level,128,128,32,targets);
        var actual=hash(level,128,128,8,targets);actual.putAll(hash(level,152,152,8,targets));
        if(!actual.keySet().equals(reference.keySet())) throw new IllegalStateException("Tile coverage mismatch");
        for(long key:targets) if(!Arrays.equals(reference.get(key),actual.get(key)))
            throw new IllegalStateException("Tile context changed block/biome/light at "+new ChunkPos(key));
        org.slf4j.LoggerFactory.getLogger("lod_only_verification").info(
                "VERIFY_TILE_ACCURACY chunks={} voxels={} structures=true referenceTile=32 actualTile=8 PASS",
                targets.size(),(long)targets.size()*level.getHeight()*256);
    }
    private static Map<Long,byte[]> hash(ServerLevel level,int ox,int oz,int tile,Set<Long> targets) throws Exception {
        var eligible=new HashSet<Long>();
        for(int z=oz;z<oz+tile;z++) for(int x=ox;x<ox+tile;x++) eligible.add(ChunkPos.asLong(x,z));
        var batch=new InMemoryGenerator(level,()->false,Runnable::run,1,tile).generateAt(ox,oz,eligible,true);
        var result=new HashMap<Long,byte[]>();var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        for(var chunk:batch.output()) {
            if(!targets.contains(chunk.getPos().toLong())) continue;
            var digest=MessageDigest.getInstance("SHA-256");byte[] value=new byte[9];
            var buffer=java.nio.ByteBuffer.wrap(value);
            for(int i=0;i<chunk.getSections().length;i++) {
                var section=chunk.getSection(i);var pos=net.minecraft.core.SectionPos.of(chunk.getPos(),chunk.getMinSection()+i);
                var sky=batch.light().getLayerListener(LightLayer.SKY).getDataLayerData(pos);
                var block=batch.light().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
                for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                    buffer.clear();buffer.putInt(Block.BLOCK_STATE_REGISTRY.getId(section.getBlockState(x,y,z)));
                    buffer.putInt(registry.getId(section.getBiomes().get(x>>2,y>>2,z>>2).value()));
                    buffer.put((byte)((sky==null?0:sky.get(x,y,z))|((block==null?0:block.get(x,y,z))<<4)));
                    digest.update(value);
                }
            }
            result.put(chunk.getPos().toLong(),digest.digest());
        }
        return result;
    }
}
