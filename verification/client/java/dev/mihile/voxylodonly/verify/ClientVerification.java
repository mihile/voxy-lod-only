package dev.mihile.voxylodonly.verify;

import com.dripps.voxyserver.client.ClientLodSettings;
import me.cortex.voxy.commonImpl.*;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.world.other.Mapper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.nio.file.*;
import java.util.*;

public class ClientVerification implements ClientModInitializer {
    private int phase;
    private long since = System.nanoTime(), start = since;
    private final List<String> results = new ArrayList<>();
    private double elapsed() { return (System.nanoTime() - since) / 1e9; }
    private void next(int p) { phase = p; since = System.nanoTime(); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void log(String line) { results.add(line); System.out.println("LOD_ONLY_CLIENT_TEST " + line); }
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if ((System.nanoTime() - start) / 1e9 > (Boolean.getBoolean("voxy.lod.activitytest") ? 300 : 160)) throw new AssertionError("Client timeout phase " + phase);
                switch (phase) {
                    case 0 -> {
                        if (mc.getOverlay() == null && elapsed() > 2) {
                            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString("127.0.0.1:25587"),
                                    new ServerData("isolated", "127.0.0.1:25587", false), false);
                            next(1);
                        }
                    }
                    case 1 -> {
                        if (mc.player != null && mc.level != null && ClientLodSettings.isProtocolOk() && elapsed() > 6) {
                            mc.getConnection().sendCommand("gamemode spectator");
                            mc.getConnection().sendCommand("tp @s 3848 180 4104 -90 20");
                            next(2);
                        }
                    }
                    case 2 -> {
                        if (elapsed() < 20 || mc.level == null || mc.player == null) return;
                        require(mc.player.getX() > 3800, "teleport did not complete");
                        var renderer = ((IGetVoxyRenderSystem)mc.levelRenderer).voxy$getRenderSystem();
                        require(renderer != null, "renderer absent");
                        var world = WorldIdentifier.of(mc.level).getOrCreateEngine();
                        require(renderer.getEngine() == world, "renderer engine mismatch");
                        require(mc.level.getChunkSource().getChunk(256, 256, ChunkStatus.FULL, false) == null,
                                "remote target unexpectedly received as a vanilla client chunk");
                        long nonAir = 0;
                        for (int y = mc.level.getMinSection() >> 1; y <= (mc.level.getMaxSection() - 1) >> 1; y++) {
                            var section = world.acquireIfExists(0, 128, y, 128);
                            if (section == null) continue;
                            try { for (long voxel : section._unsafeGetRawDataArray()) if (!Mapper.isAir(voxel)) nonAir++; }
                            finally { section.release(); }
                        }
                        require(nonAir > 1000, "generated distant LOD never reached client storage");
                        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
                        require(!instance.getStorageBasePath().toString().contains("UNKNOWN"), "UNKNOWN cache root");
                        List<String> debug = new ArrayList<>(); renderer.addDebugInfo(debug);
                        log("REMOTE_LOD vanillaChunkAbsent=true nonAir=" + nonAir + " renderer=" + String.join(" | ", debug));
                        if (Boolean.getBoolean("voxy.lod.activitytest")) {
                            log("ACTIVITY_READY"); next(10);
                        } else {
                            mc.getConnection().sendCommand("voxylodonly automatic on"); next(3);
                        }
                    }
                    case 3 -> {
                        if (elapsed() < 12) return;
                        mc.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("verification done"));
                        mc.clearLevel(); mc.setScreen(new TitleScreen()); next(4);
                    }
                    case 4 -> {
                        if (elapsed() < 3) return;
                        require(VoxyCommon.getInstance() == null && !ClientSessionEvents.inSession, "client session survived disconnect");
                        log("PASS");
                        Files.write(mc.gameDirectory.toPath().resolve("lod-only-client-result.txt"), results);
                        next(5); mc.stop();
                    }
                    case 10, 13 -> {
                        Path trigger = mc.gameDirectory.toPath().resolve("activity-disconnect");
                        if (!Files.exists(trigger)) return;
                        Files.delete(trigger);
                        mc.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("activity verification"));
                        mc.clearLevel(); mc.setScreen(new TitleScreen());
                        log("ACTIVITY_DISCONNECTED"); next(11);
                    }
                    case 11 -> {
                        require(VoxyCommon.getInstance() == null && !ClientSessionEvents.inSession,"session survived policy disconnect");
                        if (Files.exists(mc.gameDirectory.toPath().resolve("activity-finish"))) { next(4); return; }
                        Path trigger = mc.gameDirectory.toPath().resolve("activity-reconnect");
                        if (!Files.exists(trigger)) return;
                        Files.delete(trigger);
                        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString("127.0.0.1:25587"),
                                new ServerData("isolated", "127.0.0.1:25587", false), false); next(12);
                    }
                    case 12 -> {
                        if (mc.player == null || mc.level == null || !ClientLodSettings.isProtocolOk() || elapsed() < 6) return;
                        require(!((VoxyClientInstance)VoxyCommon.getInstance()).getStorageBasePath().toString().contains("UNKNOWN"), "UNKNOWN reconnect root");
                        log("ACTIVITY_RECONNECTED"); next(13);
                    }
                }
            } catch (Throwable failure) {
                failure.printStackTrace(); log("FAIL " + failure);
                try { Files.write(mc.gameDirectory.toPath().resolve("lod-only-client-result.txt"), results); } catch (Exception ignored) {}
                next(5); mc.stop();
            }
        });
    }
}
