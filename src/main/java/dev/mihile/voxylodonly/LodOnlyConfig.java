package dev.mihile.voxylodonly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class LodOnlyConfig {
    public boolean enabled = true;
    public boolean automatic = true;
    public int radiusChunks = 32;
    public int cooldownTicks = 1;
    public int commitChunksPerTick = 16;
    public int generationThreads = 0;
    public int generationTileSize = 32;
    public int maxInFlightBatches = 2;
    public double commitBudgetMillis = 3;
    public double pauseAboveTickMillis = 45;
    public boolean structures = true;
    public List<String> dimensions = List.of("minecraft:overworld");
    public static LodOnlyConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("voxy-lod-only.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            LodOnlyConfig config = Files.exists(file) ? gson.fromJson(Files.readString(file), LodOnlyConfig.class) : new LodOnlyConfig();
            if (config == null || config.radiusChunks < 1 || config.radiusChunks > 128
                    || config.cooldownTicks < 1 || config.commitChunksPerTick < 1 || config.commitChunksPerTick > 16
                    || config.generationThreads < 0 || config.generationThreads > 8
                    || (config.generationTileSize != 4 && config.generationTileSize != 8 && config.generationTileSize != 16 && config.generationTileSize != 32)
                    || config.maxInFlightBatches < 1 || config.maxInFlightBatches > 2
                    || !Double.isFinite(config.commitBudgetMillis) || config.commitBudgetMillis < 1 || config.commitBudgetMillis > 10
                    || !Double.isFinite(config.pauseAboveTickMillis) || config.pauseAboveTickMillis < 1
                    || config.dimensions == null) throw new IllegalArgumentException("Invalid Voxy LOD Only config");
            if (!Files.exists(file)) Files.writeString(file, gson.toJson(config));
            return config;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot load " + file, e); }
    }
    public int effectiveGenerationThreads() {
        return generationThreads == 0 ? Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)) : generationThreads;
    }
}
