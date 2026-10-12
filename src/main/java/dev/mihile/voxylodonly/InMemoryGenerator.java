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
import java.util.function.BooleanSupplier;

/** Owns only ProtoChunks. Never registers a ChunkHolder, ticket, entity, or region-file writer. */
public final class InMemoryGenerator {
    public static final int TILE = 4;
    private final ServerLevel level;
    private final BooleanSupplier cancelled;
    private final ChunkGenerator generator;
    private final Map<Long, ProtoChunk> chunks = new HashMap<>();

    public record Batch(int tileX, int tileZ, List<ProtoChunk> output, LevelLightEngine light) {}

    public InMemoryGenerator(ServerLevel level, BooleanSupplier cancelled) {
        this.level = level;
        this.cancelled = cancelled;
        this.generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator) && !(generator instanceof FlatLevelSource)) {
            throw new IllegalArgumentException("Unsupported custom ChunkGenerator: " + generator.getClass().getName());
        }
    }

    public Batch generate(int tileX, int tileZ, Set<Long> eligible, boolean structures) {
        int minX = Math.multiplyExact(tileX, TILE), minZ = Math.multiplyExact(tileZ, TILE);
        // Two terrain chunks around output: decorations write into a neighbor; carvers read eight.
        int terrainMinX = minX - 2, terrainMinZ = minZ - 2;
        int terrainMaxX = minX + TILE + 1, terrainMaxZ = minZ + TILE + 1;
        try (var ignored = GenerationGuard.enter()) {
            for (int z = terrainMinZ - 8; z <= terrainMaxZ + 8; z++) {
                for (int x = terrainMinX - 8; x <= terrainMaxX + 8; x++) {
                    check();
                    ProtoChunk chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, level,
                            level.registryAccess().registryOrThrow(Registries.BIOME), null);
                    chunks.put(chunk.getPos().toLong(), chunk);
                    if (structures && level.getServer().getWorldData().worldGenOptions().generateStructures()) {
                        generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                                level.structureManager(), chunk, level.getStructureManager());
                    }
                    // Deliberately no ServerLevel.onStructureStartsAvailable: that belongs to real chunks.
                    chunk.setStatus(ChunkStatus.STRUCTURE_STARTS);
                }
            }
            List<ProtoChunk> terrain = square(terrainMinX, terrainMinZ, terrainMaxX, terrainMaxZ);
            for (ProtoChunk chunk : terrain) {
                check();
                var region = region(chunk, ChunkStatus.STRUCTURE_REFERENCES, -1);
                generator.createReferences(region, level.structureManager().forWorldGenRegion(region), chunk);
                chunk.setStatus(ChunkStatus.STRUCTURE_REFERENCES);
            }
            for (ProtoChunk chunk : terrain) {
                check();
                var region = region(chunk, ChunkStatus.BIOMES, -1);
                await(generator.createBiomes(Runnable::run, level.getChunkSource().randomState(),
                        Blender.of(region), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.BIOMES);
            }
            for (ProtoChunk chunk : terrain) {
                check();
                var region = region(chunk, ChunkStatus.NOISE, 0);
                await(generator.fillFromNoise(Runnable::run, Blender.of(region),
                        level.getChunkSource().randomState(), level.structureManager().forWorldGenRegion(region), chunk));
                chunk.setStatus(ChunkStatus.NOISE);
            }
            for (ProtoChunk chunk : terrain) {
                check();
                var region = region(chunk, ChunkStatus.SURFACE, 0);
                generator.buildSurface(region, level.structureManager().forWorldGenRegion(region), level.getChunkSource().randomState(), chunk);
                chunk.setStatus(ChunkStatus.SURFACE);
            }
            for (ProtoChunk chunk : terrain) {
                check();
                var region = region(chunk, ChunkStatus.CARVERS, 0);
                generator.applyCarvers(region, level.getSeed(), level.getChunkSource().randomState(),
                        region.getBiomeManager(), level.structureManager().forWorldGenRegion(region), chunk, GenerationStep.Carving.AIR);
                chunk.setStatus(ChunkStatus.CARVERS);
                Heightmap.primeHeightmaps(chunk, ChunkStatus.POST_FEATURES);
            }
            // Stable z/x order. Neighbor decorations can contribute blocks to the output tile.
            for (ProtoChunk chunk : square(minX - 1, minZ - 1, minX + TILE, minZ + TILE)) {
                check();
                var region = region(chunk, ChunkStatus.FEATURES, 1);
                generator.applyBiomeDecoration(region, chunk, level.structureManager().forWorldGenRegion(region));
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
            List<ProtoChunk> output = square(minX, minZ, minX + TILE - 1, minZ + TILE - 1).stream()
                    .filter(chunk -> eligible.contains(chunk.getPos().toLong())).toList();
            return new Batch(tileX, tileZ, output, light);
        }
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
