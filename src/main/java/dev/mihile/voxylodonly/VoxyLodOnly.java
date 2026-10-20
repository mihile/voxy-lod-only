package dev.mihile.voxylodonly;

import com.dripps.voxyserver.Voxyserver;
import dev.mihile.voxylodonly.mixin.VoxyServerAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.*;

public final class VoxyLodOnly implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("voxy_lod_only");
    private static LodOnlyManager manager;
    private static MinecraftServer startingServer;
    private static Voxyserver startingBridge;
    @Override public void onInitialize() {
        LodOnlyConfig config = LodOnlyConfig.load();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()) { LOGGER.info("Voxy LOD Only requires a dedicated VoxyServer; integrated server remains unchanged"); return; }
            Voxyserver bridge = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                    .filter(Voxyserver.class::isInstance).map(Voxyserver.class::cast).findFirst().orElseThrow();
            startingServer = server;
            startingBridge = bridge;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> { if (manager != null) manager.markRealChunk(level, chunk.getPos()); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Fabric entrypoint callback order is not dependency order. First tick follows all SERVER_STARTED callbacks.
            if (manager == null && startingServer == server && startingBridge != null) {
                var engine = ((VoxyServerAccessor)startingBridge).lodOnly$getEngine();
                if (engine != null) {
                    manager = new LodOnlyManager(server, engine, config);
                    LOGGER.info("Voxy LOD Only started: radius={} chunks, private in-memory generation", config.radiusChunks);
                }
            }
            if (manager != null) manager.tick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(VoxyLodOnly::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (manager != null) { manager.saveCheckpoints(); manager = null; }
            startingServer = null; startingBridge = null;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LodCommands.register(dispatcher, () -> manager);
        });
    }
    public static void stop(MinecraftServer server) { if (manager != null) manager.shutdown(); }
}
