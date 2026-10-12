package dev.mihile.voxylodonly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns only ProtoChunks. Never registers a ChunkHolder, ticket, entity, or region-file writer. */
public final class InMemoryGenerator {
    public static final int TILE = 4;
    private final ServerLevel level;
    private final BooleanSupplier cancelled;
    private final ChunkGenerator generator;
    private final Map<Long, ProtoChunk> chunks = new HashMap<>();
    private final Executor stageExecutor;
    private final int parallelism, tileSize;

    public record Batch(int tileX, int tileZ, List<ProtoChunk> output, LevelLightEngine light) {}

    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled) {
        this(level, cancelled, Runnable::run, 1, TILE);
    }
    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled, Executor stageExecutor, int parallelism, int tileSize) {
        this.level = level;
        this.cancelled = cancelled;
        this.generator = level.getChunkSource().getGenerator();
        this.stageExecutor = stageExecutor; this.parallelism = parallelism; this.tileSize = tileSize;
        if (!(generator instanceof NoiseBasedChunkGenerator) && !(generator instanceof FlatLevelSource)) {
            throw new IllegalArgumentException("Unsupported custom ChunkGenerator: " + generator.getClass().getName());
        }
    }

    public Batch generate(int tileX, int tileZ, Set<Long> eligible, boolean structures) {
        int minX = Math.multiplyExact(tileX, tileSize), minZ = Math.multiplyExact(tileZ, tileSize);
        // Two terrain chunks around output: decorations write into a neighbor; carvers read eight.
        int terrainMinX = minX - 2, terrainMinZ = minZ - 2;
        int terrainMaxX = minX + tileSize + 1, terrainMaxZ = minZ + tileSize + 1;
        try (var ignored = GenerationGuard.enter()) {
            for (int z = terrainMinZ - 8; z <= terrainMaxZ + 8; z++) {
                for (int x = terrainMinX - 8; x <= terrainMaxX + 8; x++) {
                    check();
                    ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, level,
                            level.registryAccess().registryOrThrow(Registries.BIOME), null);
                    chunks.put(chunk.getPos().toLong(), chunk);
                }
            }
            stage(new ArrayList<>(chunks.values()).stream().sorted(Comparator.comparingLong(c -> c.getPos().toLong())).toList(), chunk -> {
                    if (structures && level.getServer().getWorldData().worldGenOptions().generateStructures()) {
                        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                                level.structureManager(), chunk, level.getStructureManager());
                    }
                    // Deliberately no ServerLevel.onStructureStartsAvailable: that belongs to real chunks.
                    chunk.setStatus(ChunkStatus.STRUCTURE_STARTS);
            });
            List<ProtoChunk> terrain = square(terrainMinX, terrainMinZ, terrainMaxX, terrainMaxZ);
            stage(terrain, chunk -> {
                check();
                var region = region(chunk, ChunkStatus.STRUCTURE_REFERENCES, -1);
                generator.createReferences(region, level.structureManager().forWorldGenRegion(region), chunk);
                chunk.setStatus(ChunkStatus.STRUCTURE_REFERENCES);
            });
            stage(terrain, chunk -> {
                check();
                var region = region(chunk, ChunkStatus.BIOMES, -1);
                await(generator.createBiomes(Runnable::run, level.getChunkSource().randomState(),
                        Blender.of(region), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.BIOMES);
            });
            stage(terrain, chunk -> {
                check();
                var region = region(chunk, ChunkStatus.NOISE, 0);
                await(generator.fillFromNoise(Runnable::run, Blender.of(region),
                        level.getChunkSource().randomState(), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.NOISE);
            });
            stage(terrain, chunk -> {
                check();
                var region = region(chunk, ChunkStatus.SURFACE, 0);
                generator.buildSurface(region, level.structureManager().forWorldGenRegion(region), level.getChunkSource().randomState(), chunk);
                chunk.setStatus(ChunkStatus.SURFACE);
            });
            stage(terrain, chunk -> {
                check();
                var region = region(chunk, ChunkStatus.CARVERS, 0);
                generator.applyCarvers(region, level.getSeed(), level.getChunkSource().randomState(),
                        region.getBiomeManager(), level.structureManager().forWorldGenRegion(region), chunk, GenerationStep.Carving.AIR);
                chunk.setStatus(ChunkStatus.CARVERS);
                Heightmap.primeHeightmaps(chunk, ChunkStatus.POST_FEATURES);
            });
            // Stable z/x order. Neighbor decorations can contribute blocks to the output tile.
            for (ProtoChunk chunk : square(minX - 1, minZ - 1, minX + tileSize, minZ + tileSize)) {
                check();
                var region = region(chunk, ChunkStatus.FEATURES, 1);
                try (var decoration = GenerationGuard.enter(region)) {
                    // Some vanilla structure entities ask getLevel().structureManager(). Route
                    // that lookup back into this private region instead of loading real chunks.
                    generator.applyBiomeDecoration(region, chunk, level.structureManager());
                }
                chunk.setStatus(ChunkStatus.FEATURES);
            }
            LevelLightEngine light = new LevelLightEngine(new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) {
                    return chunks.get(ChunkPos.asLong(x, z));
                }
                @Override public BlockGetter getLevel() { return level; }
            }, true, level.dimensionType().hasSkyLight());
            for (ProtoChunk chunk : terrain) {
                check();
                chunk.initializeLightSources();
                for (int i = 0; i < chunk.getSections().length; i++) {
                    light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getMinSection() + i),
                            chunk.getSection(i).hasOnlyAir());
                }
                light.setLightEnabled(chunk.getPos(), true);
                light.propagateLightSources(chunk.getPos());
            }
            while (light.hasLightWork()) { check(); light.runLightUpdates(); }
            List<ProtoChunk> output = square(minX, minZ, minX + tileSize - 1, minZ + tileSize - 1).stream()
                    .filter(chunk -> eligible.contains(chunk.getPos().toLong())).toList();
            return new Batch(tileX, tileZ, output, light);
        }
    }

    private void stage(List<ProtoChunk> work, Consumer<ProtoChunk> action) {
        // One bounded task per worker. Every stage drains all siblings, even on failure/cancel.
        // The immutable region map is published before submission; each task owns its output chunk.
        int count = Math.min(parallelism, work.size());
        var futures = new ArrayList<CompletableFuture<Void>>(count);
        for (int lane = 0; lane < count; lane++) {
            final int start = lane;
            futures.add(CompletableFuture.runAsync(() -> {
                try (var guard = GenerationGuard.enter()) {
                    for (int i = start; i < work.size(); i += count) {
                        check(); action.accept(work.get(i));
                    }
                }
            }, stageExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        check();
    }

    private WorldGenRegion region(ProtoChunk chunk, ChunkStatus status, int writeRadius) {
        var pos = chunk.getPos();
        return new PrivateRegion(level, new ArrayList<ChunkAccess>(square(pos.x - 8, pos.z - 8, pos.x + 8, pos.z + 8)), status, writeRadius);
    }
    private static final class PrivateRegion extends WorldGenRegion {
        PrivateRegion(ServerLevel level, List<ChunkAccess> chunks, ChunkStatus status, int radius) { super(level, chunks, status, radius); }
        @Override public boolean isOldChunkAround(ChunkPos pos, int radius) {
            // Every chunk in this region is a newly allocated ProtoChunk. The vanilla implementation
            // delegates to ChunkMap/IOWorker and scans disk regions, creating empty .mca files.
            return false;
        }
    }
    private List<ProtoChunk> square(int x1, int z1, int x2, int z2) {
        List<ProtoChunk> result = new ArrayList<>();
        for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) {
            ProtoChunk chunk = chunks.get(ChunkPos.asLong(x, z));
            if (chunk == null) throw new IllegalStateException("Missing private generation neighbor " + x + "," + z);
            result.add(chunk);
        }
        return result;
    }
    private <T> T await(CompletableFuture<T> future) {
        // Drain the vanilla stage even after cancellation, so its asynchronous work cannot outlive us.
        T result = future.join();
        check();
        return result;
    }
    private void check() { if (cancelled.getAsBoolean()) throw new CancellationException(); }
}
