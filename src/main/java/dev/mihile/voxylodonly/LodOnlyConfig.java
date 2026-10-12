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
    public int cooldownTicks = 20;
    public int commitChunksPerTick = 1;
    public double pauseAboveTickMillis = 45;
    public boolean structures = true;
    public List<String> dimensions = List.of("minecraft:overworld");
    public static LodOnlyConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("voxy-lod-only.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            LodOnlyConfig config = Files.exists(file) ? gson.fromJson(Files.readString(file), LodOnlyConfig.class) : new LodOnlyConfig();
            if (config == null || config.radiusChunks < 1 || config.radiusChunks > 128
                    || config.cooldownTicks < 1 || config.commitChunksPerTick < 1 || config.commitChunksPerTick > 4
                    || !Double.isFinite(config.pauseAboveTickMillis) || config.pauseAboveTickMillis < 1
                    || config.dimensions == null) throw new IllegalArgumentException("Invalid Voxy LOD Only config");
            if (!Files.exists(file)) Files.writeString(file, gson.toJson(config));
            return config;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot load " + file, e); }
    }
}
