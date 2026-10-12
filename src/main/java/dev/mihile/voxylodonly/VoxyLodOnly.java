package dev.mihile.voxylodonly;

import com.dripps.voxyserver.Voxyserver;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.mihile.voxylodonly.mixin.VoxyServerAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
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
            var root = Commands.literal("voxylodonly").requires(source -> source.hasPermission(2));
            root.then(Commands.literal("status").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(manager == null ? "Voxy LOD Only inactive" : manager.status()), false); return 1;
            }));
            root.then(Commands.literal("cancel").executes(ctx -> {
                if (manager != null) manager.cancel();
                ctx.getSource().sendSuccess(() -> Component.literal("Voxy LOD Only job cancelled; generation drains before reuse"), false); return 1;
            }));
            root.then(Commands.literal("automatic").then(Commands.literal("on").executes(ctx -> { if (manager != null) manager.automatic(true); return 1; }))
                    .then(Commands.literal("off").executes(ctx -> { if (manager != null) manager.automatic(false); return 1; })));
            root.then(Commands.literal("generate")
                    .then(Commands.argument("dimension", ResourceLocationArgument.id())
                    .then(Commands.argument("chunkX", IntegerArgumentType.integer(-1_875_000, 1_875_000))
                    .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-1_875_000, 1_875_000))
                    .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 128)).executes(ctx -> {
                        if (manager == null) { ctx.getSource().sendFailure(Component.literal("Dedicated VoxyServer required")); return 0; }
                        var dimension = ResourceLocationArgument.getId(ctx, "dimension");
                        var level = ctx.getSource().getServer().getAllLevels().iterator();
                        while (level.hasNext()) {
                            var candidate = level.next();
                            if (candidate.dimension().location().equals(dimension)) {
                                boolean accepted = manager.generate(candidate, IntegerArgumentType.getInteger(ctx, "chunkX"),
                                        IntegerArgumentType.getInteger(ctx, "chunkZ"), IntegerArgumentType.getInteger(ctx, "radiusChunks"));
                                if (accepted) ctx.getSource().sendSuccess(() -> Component.literal("Voxy LOD Only generation started"), false);
                                else ctx.getSource().sendFailure(Component.literal("Generation busy or disabled; use status/cancel"));
                                return accepted ? 1 : 0;
                            }
                        }
                        ctx.getSource().sendFailure(Component.literal("Unknown dimension " + dimension)); return 0;
                    }))))));
            dispatcher.register(root);
        });
    }
    public static void stop(MinecraftServer server) { if (manager != null) manager.shutdown(); }
}
