package dev.mihile.voxylodonly.verify;

import com.dripps.voxyserver.Voxyserver;
import dev.mihile.voxylodonly.GenerationGuard;
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
}
