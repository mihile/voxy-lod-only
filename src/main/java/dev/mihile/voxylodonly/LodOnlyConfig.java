package dev.mihile.voxylodonly;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class LodOnlyConfig {
    public boolean enabled = true;
    public boolean automatic = true;
    public int radiusChunks = 32;
    public int cooldownTicks = 1;
    public int commitChunksPerTick = 64;
    public int generationThreads = 0;
    public int generationTileSize = 32;
    public int maxInFlightBatches = 2;
    public double commitBudgetMillis = 3;
    public double idleCommitBudgetMillis = 10;
    public double pauseAboveTickMillis = 45;
    public boolean structures = true;
    public boolean continueAutomaticWhenEmpty = false;
    public ActivityProfile withPlayers = new ActivityProfile();
    public ActivityProfile withoutPlayers = new ActivityProfile();
    public List<String> dimensions = List.of("minecraft:overworld");
    /** Null fields inherit the old top-level setting, preserving existing installations. */
    public static final class ActivityProfile {
        public boolean enabled = true;
        public Integer generationThreads;
        public Integer cooldownTicks;
        public Integer commitChunksPerTick;
        public Double commitBudgetMillis;
        public Double pauseAboveTickMillis;
        public int maxChunksPerSecond = 0;
    }
    public record Policy(boolean enabled, int generationThreads, int cooldownTicks,
                         int commitChunksPerTick, double commitBudgetMillis,
                         double pauseAboveTickMillis, int maxChunksPerSecond) {}
    public static LodOnlyConfig load() {
        return load(file());
    }
    public static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("voxy-lod-only.json"); }
    static LodOnlyConfig load(Path file) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            LodOnlyConfig config = Files.exists(file) ? gson.fromJson(Files.readString(file), LodOnlyConfig.class) : new LodOnlyConfig();
            if (config == null) throw new IllegalArgumentException("Empty Voxy LOD Only config");
            config.validate();
            if (!Files.exists(file)) {
                config.materializeProfiles();
                Files.writeString(file, gson.toJson(config));
            }
            return config;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot load " + file, e); }
    }
    public LodOnlyConfig withRadius(int radius) {
        Gson gson=new Gson();
        LodOnlyConfig copy=gson.fromJson(gson.toJson(this),LodOnlyConfig.class);
        copy.radiusChunks=radius; copy.validate(); return copy;
    }
    /** Persist only this property, preserving other disk edits and unknown fields. */
    public static void saveRadius(int radius) throws IOException { saveRadius(file(),radius); }
    static void saveRadius(Path file,int radius) throws IOException {
        Gson gson=new GsonBuilder().setPrettyPrinting().create();
        JsonObject json=Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():gson.toJsonTree(new LodOnlyConfig()).getAsJsonObject();
        json.addProperty("radiusChunks",radius);
        LodOnlyConfig checked=gson.fromJson(json,LodOnlyConfig.class); checked.validate();
        Path absolute=file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary=Files.createTempFile(absolute.getParent(),"voxy-lod-radius-",".tmp");
        try {
            Files.writeString(temporary,gson.toJson(json));
            try { Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public void validate() {
            if (radiusChunks < 1 || radiusChunks > 128
                    || cooldownTicks < 1 || commitChunksPerTick < 1 || commitChunksPerTick > 64
                    || generationThreads < 0 || generationThreads > 8
                    || (generationTileSize != 4 && generationTileSize != 8 && generationTileSize != 16 && generationTileSize != 32)
                    || maxInFlightBatches < 1 || maxInFlightBatches > 2
                    || !Double.isFinite(commitBudgetMillis) || commitBudgetMillis < 1 || commitBudgetMillis > 10
                    || !Double.isFinite(idleCommitBudgetMillis) || idleCommitBudgetMillis < 1 || idleCommitBudgetMillis > 10
                    || !Double.isFinite(pauseAboveTickMillis) || pauseAboveTickMillis < 1
                    || dimensions == null || withPlayers == null || withoutPlayers == null)
                throw new IllegalArgumentException("Invalid Voxy LOD Only config");
            validateProfile(withPlayers); validateProfile(withoutPlayers);
    }
    private void validateProfile(ActivityProfile p) {
        if ((p.generationThreads != null && (p.generationThreads < 0 || p.generationThreads > 8))
                || (p.cooldownTicks != null && p.cooldownTicks < 1)
                || (p.commitChunksPerTick != null && (p.commitChunksPerTick < 1 || p.commitChunksPerTick > 64))
                || (p.commitBudgetMillis != null && (!Double.isFinite(p.commitBudgetMillis) || p.commitBudgetMillis < 1 || p.commitBudgetMillis > 10))
                || (p.pauseAboveTickMillis != null && (!Double.isFinite(p.pauseAboveTickMillis) || p.pauseAboveTickMillis < 1))
                || p.maxChunksPerSecond < 0 || p.maxChunksPerSecond > 10000)
            throw new IllegalArgumentException("Invalid Voxy LOD Only activity profile");
    }
    private void materializeProfiles() {
        for (boolean occupied : new boolean[]{false, true}) {
            var p = occupied ? withPlayers : withoutPlayers;
            p.generationThreads = generationThreads;
            p.cooldownTicks = cooldownTicks;
            p.commitChunksPerTick = commitChunksPerTick;
            p.commitBudgetMillis = occupied ? commitBudgetMillis : idleCommitBudgetMillis;
            p.pauseAboveTickMillis = pauseAboveTickMillis;
        }
    }
    public Policy policy(boolean playersPresent) {
        var p = playersPresent ? withPlayers : withoutPlayers;
        int threads = p.generationThreads == null ? generationThreads : p.generationThreads;
        return new Policy(enabled && p.enabled,
                threads == 0 ? automaticWorkers(Runtime.getRuntime().availableProcessors(), playersPresent) : threads,
                p.cooldownTicks == null ? cooldownTicks : p.cooldownTicks,
                p.commitChunksPerTick == null ? commitChunksPerTick : p.commitChunksPerTick,
                p.commitBudgetMillis == null ? (playersPresent ? commitBudgetMillis : idleCommitBudgetMillis) : p.commitBudgetMillis,
                p.pauseAboveTickMillis == null ? pauseAboveTickMillis : p.pauseAboveTickMillis,
                p.maxChunksPerSecond);
    }
    public int effectiveGenerationThreads() {
        return Math.max(activeGenerationThreads(false), activeGenerationThreads(true));
    }
    public int activeGenerationThreads(boolean playersPresent) {
        return policy(playersPresent).generationThreads();
    }
    static int automaticWorkers(int processors,boolean playersPresent) {
        return Math.max(1,Math.min(8,processors-(playersPresent?1:0)));
    }
    public double activeCommitBudgetMillis(boolean playersPresent) {
        return policy(playersPresent).commitBudgetMillis();
    }
}
