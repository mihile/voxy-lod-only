package dev.mihile.voxylodonly;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

final class LodCommands {
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<LodOnlyManager> manager) {
        var root=Commands.literal("voxylod").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("reload").executes(ctx -> {
            if (manager.get()==null) { ctx.getSource().sendFailure(Component.literal("Dedicated VoxyServer required")); return 0; }
            try {
                manager.get().reload(LodOnlyConfig.load());
                ctx.getSource().sendSuccess(() -> Component.literal("LOD config reload accepted; applies after pending work drains, manual progress retained"),false);
                return 1;
            } catch (RuntimeException e) {
                ctx.getSource().sendFailure(Component.literal("LOD config reload failed; current settings retained: "+e.getMessage())); return 0;
            }
        }));
        root.then(Commands.literal("radius").then(Commands.argument("chunks",IntegerArgumentType.integer(1,128)).executes(ctx -> {
            if (manager.get()==null) { ctx.getSource().sendFailure(Component.literal("Dedicated VoxyServer required")); return 0; }
            int radius=IntegerArgumentType.getInteger(ctx,"chunks");
            try {
                LodOnlyConfig.saveRadius(radius); manager.get().radius(radius);
                ctx.getSource().sendSuccess(() -> Component.literal("LOD automatic radius applied and saved: "+radius+" chunks; explicit manual area unchanged"),false);
                return 1;
            } catch (java.io.IOException | RuntimeException e) {
                ctx.getSource().sendFailure(Component.literal("LOD radius update failed; current settings retained: "+e.getMessage())); return 0;
            }
        })));
        root.then(Commands.literal("status").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(manager.get()==null?"Voxy LOD Only inactive":manager.get().status()),false);
            return 1;
        }));
        root.then(Commands.literal("pause").executes(ctx -> {
            if (manager.get()==null || !manager.get().pause()) {
                ctx.getSource().sendFailure(Component.literal("No active LOD job to pause")); return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal("LOD job paused; progress retained, running private tasks finish safely"),false); return 1;
        }));
        root.then(Commands.literal("resume").executes(ctx -> {
            if (manager.get()==null || !manager.get().resume()) {
                ctx.getSource().sendFailure(Component.literal("No manually paused LOD job")); return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal("LOD job resumed; occupancy profile still applies"),false); return 1;
        }));
        root.then(Commands.literal("stop").executes(ctx -> {
            if (manager.get()==null) { ctx.getSource().sendFailure(Component.literal("Dedicated VoxyServer required")); return 0; }
            manager.get().stopWork();
            ctx.getSource().sendSuccess(() -> Component.literal("LOD job stopped; pending work drains, saved LOD retained, automatic generation off"),false); return 1;
        }));
        root.then(Commands.literal("automatic")
                .then(Commands.literal("on").executes(ctx -> { if (manager.get()==null) return 0; manager.get().automatic(true); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { if (manager.get()==null) return 0; manager.get().automatic(false); return 1; })));
        var z=Commands.argument("centerZ",IntegerArgumentType.integer(-29_999_999,29_999_999));
        for (var shape:GenerationArea.Shape.values()) {
            z.then(Commands.literal(shape.name()).then(Commands.argument("radiusBlocks",IntegerArgumentType.integer(0,GenerationArea.MAX_RADIUS))
                    .then(Commands.literal("start").executes(ctx -> {
                        var area=new GenerationArea(IntegerArgumentType.getInteger(ctx,"centerX"),IntegerArgumentType.getInteger(ctx,"centerZ"),
                                shape,IntegerArgumentType.getInteger(ctx,"radiusBlocks"));
                        if (manager.get()==null || !manager.get().startArea(ctx.getSource().getLevel(),area)) {
                            ctx.getSource().sendFailure(Component.literal("LOD job busy or disabled; use /voxylod status or stop, then wait for cleanup")); return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("LOD area accepted: dimension="+ctx.getSource().getLevel().dimension().location()
                                +", "+area.description()+", candidates="+area.totalChunks()+"; automatic generation off"),false);
                        return 1;
                    }))));
        }
        root.then(Commands.argument("centerX",IntegerArgumentType.integer(-29_999_999,29_999_999)).then(z));
        dispatcher.register(root);
    }
}
