package dev.mihile.voxylodonly.verify;

import com.dripps.voxyserver.Voxyserver;
import dev.mihile.voxylodonly.GenerationGuard;
import dev.mihile.voxylodonly.InMemoryGenerator;
import dev.mihile.voxylodonly.mixin.VoxyServerAccessor;
import me.cortex.voxy.common.world.other.Mapper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

public class VerificationMod implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("lodonlytest").requires(source -> source.hasPermission(4));
            root.then(Commands.literal("conversion").executes(ctx -> {
                var bridge=FabricLoader.getInstance().getEntrypoints("main",ModInitializer.class).stream()
                        .filter(Voxyserver.class::isInstance).map(Voxyserver.class::cast).findFirst().orElseThrow();
                var engine=((VoxyServerAccessor)bridge).lodOnly$getEngine();
                var level=ctx.getSource().getLevel();var world=engine.getOrCreate(level);world.acquireRef();
                Thread checker=new Thread(() -> {
                    try { dev.mihile.voxylodonly.ConversionVerification.run(level,world);
                        LoggerFactory.getLogger("lod_only_verification").info("VERIFY_CONVERSION sections=384 workers=3 cancel/reject/lease/packed-data PASS"); }
                    catch(Throwable e) { LoggerFactory.getLogger("lod_only_verification").error("VERIFY_PARALLEL FAILURE conversion",e); }
                    finally { world.releaseRef(); }
                },"LOD conversion verifier");checker.setDaemon(true);checker.start();return 1;
            }));
            root.then(Commands.literal("parallel").executes(ctx -> {
                var server = ctx.getSource().getServer();
                Thread checker = new Thread(() -> {
                    var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
                    try {
                        verifyTemplateCaches(pool);
                        for (int tileSize : new int[]{4, 8, 16, 32}) for (var dimension : java.util.List.of(net.minecraft.world.level.Level.OVERWORLD, net.minecraft.world.level.Level.NETHER)) {
                            var level = server.getLevel(dimension);
                            var eligible = new java.util.HashSet<Long>();
                            for (int z = 128; z < 128+tileSize; z++) for (int x = 128; x < 128+tileSize; x++)
                                eligible.add(net.minecraft.world.level.ChunkPos.asLong(x, z));
                            var serial = new InMemoryGenerator(level, () -> false, Runnable::run, 1, tileSize).generate(128/tileSize, 128/tileSize, eligible, true);
                            var parallel = new InMemoryGenerator(level, () -> false, pool, 3, tileSize).generate(128/tileSize, 128/tileSize, eligible, true);
                            if (serial.output().size() != tileSize*tileSize || parallel.output().size() != tileSize*tileSize) throw new IllegalStateException("Missing output");
                            long checked = 0;
                            for (int c = 0; c < tileSize*tileSize; c++) {
                                var a = serial.output().get(c); var b = parallel.output().get(c);
                                if (!a.getPos().equals(b.getPos())) throw new IllegalStateException("Changed output order");
                                for (int s = 0; s < a.getSections().length; s++) {
                                    var pa = a.getSection(s); var pb = b.getSection(s);
                                    var pos = net.minecraft.core.SectionPos.of(a.getPos(), a.getMinSection()+s);
                                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                        if (pa.getBlockState(x,y,z) != pb.getBlockState(x,y,z)
                                                || !pa.getBiomes().get(x>>2,y>>2,z>>2).equals(pb.getBiomes().get(x>>2,y>>2,z>>2)))
                                            throw new IllegalStateException("Parallel terrain mismatch at "+pos+":"+x+","+y+","+z);
                                        for (var layer : net.minecraft.world.level.LightLayer.values()) {
                                            var la=serial.light().getLayerListener(layer).getDataLayerData(pos);
                                            var lb=parallel.light().getLayerListener(layer).getDataLayerData(pos);
                                            if ((la==null?0:la.get(x,y,z)) != (lb==null?0:lb.get(x,y,z)))
                                                throw new IllegalStateException("Parallel light mismatch at "+pos);
                                        }
                                        checked++;
                                    }
                                }
                            }
                            LoggerFactory.getLogger("lod_only_verification").info("VERIFY_PARALLEL dimension={} tileSize={} voxels={} PASS", dimension.location(), tileSize, checked);
                            // A corner at a cropped boundary must retain the full tile's blocks,
                            // biomes and light. This catches accidentally removing a dependency halo.
                            for (int offset : new int[]{0,tileSize-1}) {
                                var corner = java.util.Set.of(net.minecraft.world.level.ChunkPos.asLong(128+offset,128+offset));
                                var cropped = new InMemoryGenerator(level, () -> false, pool, 3, tileSize)
                                        .generate(128/tileSize,128/tileSize,corner,true);
                                if (cropped.output().size()!=1) throw new IllegalStateException("Crop missing output");
                                compareCorner(serial, cropped);
                            }
                            LoggerFactory.getLogger("lod_only_verification").info("VERIFY_CROP dimension={} tileSize={} PASS", dimension.location(), tileSize);
                        }
                        LoggerFactory.getLogger("lod_only_verification").info("VERIFY_PARALLEL_ALL PASS");
                    } catch (Throwable e) { LoggerFactory.getLogger("lod_only_verification").error("VERIFY_PARALLEL FAILURE",e); }
                    finally { pool.shutdown(); }
                }, "LOD parallel verifier");
                checker.setDaemon(true); checker.start(); return 1;
            }));
            root.then(Commands.literal("inspect").executes(ctx -> {
                Voxyserver bridge = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                        .filter(Voxyserver.class::isInstance).map(Voxyserver.class::cast).findFirst().orElseThrow();
                var engine = ((VoxyServerAccessor)bridge).lodOnly$getEngine();
                for (var level : ctx.getSource().getServer().getAllLevels()) {
                    var world = engine.getOrCreate(level);
                    long nonAir = 0, sky = 0, gold = 0, positions = 0;
                    for (int y = level.getMinSection() >> 1; y <= (level.getMaxSection() - 1) >> 1; y++) {
                        var section = world.acquireIfExists(0, 64, y, 64);
                        if (section == null) continue;
                        positions++;
                        try {
                            long[] data = section._unsafeGetRawDataArray();
                            for (int vy = 0; vy < 32; vy++) for (int vz = 0; vz < 16; vz++) for (int vx = 0; vx < 16; vx++) {
                                long voxel = data[me.cortex.voxy.common.world.WorldSection.getIndex(vx, vy, vz)];
                                if (Mapper.getBlockId(voxel) != 0) nonAir++;
                                if ((Mapper.getLightId(voxel) & 15) != 0) sky++;
                                if (world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(voxel)).is(Blocks.GOLD_BLOCK)) gold++;
                            }
                        } finally { section.release(); }
                    }
                    LoggerFactory.getLogger("lod_only_verification").info("VERIFY_STORAGE dimension={} positions={} nonAir={} sky={} gold={}",
                            level.dimension().location(), positions, nonAir, sky, gold);
                }
                return 1;
            }));
            root.then(Commands.literal("guard").executes(ctx -> {
                boolean rejected = false;
                try (var ignored = GenerationGuard.enter()) {
                    ctx.getSource().getLevel().setBlock(new BlockPos(0, 250, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                } catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("Guard accepted live-world mutation");
                rejected = false;
                try (var ignored = GenerationGuard.enter()) {
                    ctx.getSource().getLevel().getChunkSource().getChunk(0, 0, net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
                } catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("Guard accepted server chunk acquisition");
                rejected = false;
                try (var ignored = GenerationGuard.enter()) {
                    ctx.getSource().getLevel().addFreshEntity(new net.minecraft.world.entity.decoration.ArmorStand(ctx.getSource().getLevel(), 0, 250, 0));
                } catch (IllegalStateException expected) { rejected = true; }
                if (!rejected) throw new IllegalStateException("Guard accepted live entity spawn");
                LoggerFactory.getLogger("lod_only_verification").info("VERIFY_GUARD PASS"); return 1;
            }));
            root.then(Commands.literal("visit").executes(ctx -> {
                var level = ctx.getSource().getServer().overworld();
                level.getChunk(128, 128);
                level.setBlock(new BlockPos(2056, 200, 2056), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                LoggerFactory.getLogger("lod_only_verification").info("VERIFY_NORMAL_VISIT marker={}", level.getBlockState(new BlockPos(2056, 200, 2056)).is(Blocks.GOLD_BLOCK)); return 1;
            }));
            root.then(Commands.literal("checkmarker").executes(ctx -> {
                var level = ctx.getSource().getServer().overworld();
                boolean present = level.getBlockState(new BlockPos(2056, 200, 2056)).is(Blocks.GOLD_BLOCK);
                LoggerFactory.getLogger("lod_only_verification").info("VERIFY_MARKER persisted={}", present);
                if (!present) throw new IllegalStateException("Normal vanilla save lost marker"); return 1;
            }));
            root.then(Commands.literal("visitrace").executes(ctx -> {
                var level = ctx.getSource().getServer().overworld();
                level.getChunk(384, 384);
                level.setBlock(new BlockPos(6152, 200, 6152), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                LoggerFactory.getLogger("lod_only_verification").info("VERIFY_INFLIGHT_VISIT marker={}", level.getBlockState(new BlockPos(6152, 200, 6152)).is(Blocks.GOLD_BLOCK)); return 1;
            }));
            root.then(Commands.literal("checkrace").executes(ctx -> {
                boolean present = ctx.getSource().getServer().overworld().getBlockState(new BlockPos(6152, 200, 6152)).is(Blocks.GOLD_BLOCK);
                if (!present) throw new IllegalStateException("Race lost player's real block");
                LoggerFactory.getLogger("lod_only_verification").info("VERIFY_INFLIGHT_VISIT_PERSISTENCE PASS"); return 1;
            }));
            dispatcher.register(root);
        });
    }

    private static void compareCorner(InMemoryGenerator.Batch full, InMemoryGenerator.Batch cropped) {
        var b=cropped.output().get(0);
        var a=full.output().stream().filter(chunk -> chunk.getPos().equals(b.getPos())).findFirst().orElseThrow();
        for(int s=0;s<a.getSections().length;s++) {
            var pa=a.getSection(s); var pb=b.getSection(s);
            var pos=net.minecraft.core.SectionPos.of(a.getPos(),a.getMinSection()+s);
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                if(pa.getBlockState(x,y,z)!=pb.getBlockState(x,y,z)
                        || !pa.getBiomes().get(x>>2,y>>2,z>>2).equals(pb.getBiomes().get(x>>2,y>>2,z>>2)))
                    throw new IllegalStateException("Crop terrain mismatch at "+pos+":"+x+","+y+","+z+" full="+pa.getBlockState(x,y,z)+" crop="+pb.getBlockState(x,y,z));
                for(var layer:net.minecraft.world.level.LightLayer.values()) {
                    var la=full.light().getLayerListener(layer).getDataLayerData(pos);
                    var lb=cropped.light().getLayerListener(layer).getDataLayerData(pos);
                    if((la==null?0:la.get(x,y,z))!=(lb==null?0:lb.get(x,y,z)))
                        throw new IllegalStateException("Crop light mismatch at "+pos);
                }
            }
        }
    }

    private static void verifyTemplateCaches(java.util.concurrent.ExecutorService pool) throws Exception {
        var infos=new java.util.ArrayList<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo>();
        var blocks=net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream().limit(64).toList();
        for(int i=0;i<blocks.size();i++) infos.add(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(new BlockPos(i,0,0),blocks.get(i).defaultBlockState(),null));
        var ctor=net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette.class.getDeclaredConstructor(java.util.List.class);
        ctor.setAccessible(true);
        for(int round=0;round<64;round++) {
            var palette=ctor.newInstance(infos);
            var barrier=new java.util.concurrent.CyclicBarrier(3);
            var tasks=new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for(int lane=0;lane<3;lane++) {
                final int offset=lane;
                tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { barrier.await(); } catch(Exception e) { throw new RuntimeException(e); }
                    for(int i=0;i<blocks.size()*4;i++) {
                        int index=(i+offset)%blocks.size();
                        var result=palette.blocks(blocks.get(index));
                        if(result.size()!=1 || result.get(0)!=infos.get(index)) throw new IllegalStateException("Template cache contents changed");
                    }
                },pool));
            }
            java.util.concurrent.CompletableFuture.allOf(tasks.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }
        LoggerFactory.getLogger("lod_only_verification").info("VERIFY_TEMPLATE_CACHE coldPalettes=64 keys=64 workers=3 PASS");
    }
}
