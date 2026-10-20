package dev.mihile.voxylodonly.flight;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.GameType;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** No product dependency: the identical instrumentation runs with LOD absent, 0.8 or the candidate. */
public final class FlightVerificationMod implements ModInitializer {
    private final Gson gson=new Gson();
    private long start, tickStart, nextSample;
    private boolean measuring;
    private final List<Double> ticks=new ArrayList<>(), waits=new ArrayList<>();
    private final Map<String,Long> pending=new HashMap<>();
    private final Map<UUID,String> probeDimensions=new HashMap<>();
    private final Map<String,Long> previousCpu=new HashMap<>();
    private final Map<String,Long> cpu=new HashMap<>();
    private final List<Map<String,Object>> samples=new ArrayList<>();
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var ids=new LinkedHashMap<String,Integer>();
            packet(ids,"keepalive",PacketFlow.CLIENTBOUND,ClientboundKeepAlivePacket.class);
            packet(ids,"teleport",PacketFlow.CLIENTBOUND,ClientboundPlayerPositionPacket.class);
            packet(ids,"payload",PacketFlow.CLIENTBOUND,ClientboundCustomPayloadPacket.class);
            packet(ids,"chunk",PacketFlow.CLIENTBOUND,ClientboundLevelChunkWithLightPacket.class);
            packet(ids,"disconnect",PacketFlow.CLIENTBOUND,ClientboundDisconnectPacket.class);
            packet(ids,"join",PacketFlow.CLIENTBOUND,ClientboundLoginPacket.class);
            packet(ids,"keepaliveReply",PacketFlow.SERVERBOUND,ServerboundKeepAlivePacket.class);
            packet(ids,"teleportReply",PacketFlow.SERVERBOUND,ServerboundAcceptTeleportationPacket.class);
            packet(ids,"payloadReply",PacketFlow.SERVERBOUND,ServerboundCustomPayloadPacket.class);
            packet(ids,"position",PacketFlow.SERVERBOUND,ServerboundMovePlayerPacket.Pos.class);
            packet(ids,"settings",PacketFlow.SERVERBOUND,ServerboundClientInformationPacket.class);
            packet(ids,"abilities",PacketFlow.SERVERBOUND,ServerboundPlayerAbilitiesPacket.class);
            writeOwoLogin();
            write("flight-protocol.json",ids);
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> {
            if(!handler.player.getGameProfile().getName().startsWith("Flight")) return;
            dropProbes(handler.player.getUUID());
            completeTutorial(handler.player);
            handler.player.setGameMode(GameType.CREATIVE);
            handler.player.getAbilities().flying=true;
            handler.player.onUpdateAbilities();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> dropProbes(handler.player.getUUID()));
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart=System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(!measuring) return;
            long now=System.nanoTime();
            ticks.add(server.tickTimes[Math.floorMod(server.getTickCount()-1,server.tickTimes.length)]/1e6);
            for(var player:server.getPlayerList().getPlayers()) {
                var center=player.chunkPosition();
                String dimension=player.serverLevel().dimension().location().toString();
                if(!dimension.equals(probeDimensions.get(player.getUUID()))) {
                    dropProbes(player.getUUID());probeDimensions.put(player.getUUID(),dimension);
                }
                for(int z=-1;z<=1;z++) for(int x=-1;x<=1;x++) {
                    int cx=center.x+x,cz=center.z+z;
                    String key=player.getUUID()+":"+dimension+":"+cx+":"+cz;
                    boolean ready=player.serverLevel().getChunkSource().getChunkNow(cx,cz)!=null;
                    if(!ready) pending.putIfAbsent(key,now);
                    else { Long began=pending.remove(key); if(began!=null) waits.add((now-began)/1e6); }
                }
            }
            // Probes that leave this 3x3 remain unresolved unless encountered again.
            // This observer never requests/generates chunks or measures the engine's full queue.
            if(now>=nextSample) {
                nextSample=now+1_000_000_000L;
                var bean=ManagementFactory.getThreadMXBean();
                for(long id:bean.getAllThreadIds()) {
                    var info=bean.getThreadInfo(id); if(info==null) continue;
                    String name=info.getThreadName(); long value=bean.getThreadCpuTime(id); if(value<0) continue;
                    String category=name.startsWith("Voxy LOD Only")?"lod":name.startsWith("VoxyServer")?"voxyserver":
                            name.startsWith("Worker-Main")?"vanillaWorkers":name.equals("Server thread")?"server":"other";
                    String key=Long.toString(id); Long old=previousCpu.put(key,value);
                    if(old!=null) cpu.merge(category,Math.max(0,value-old),Long::sum);
                }
                long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
                long gcCount=0,gcMillis=0,fullGc=0;
                for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) {
                    gcCount+=gc.getCollectionCount(); gcMillis+=gc.getCollectionTime();
                    if(gc.getName().contains("Old") || gc.getName().contains("MarkSweep")) fullGc+=gc.getCollectionCount();
                }
                var sample=new LinkedHashMap<String,Object>();
                sample.put("seconds",(now-start)/1e9); sample.put("heapBytes",used);
                sample.put("gcCount",gcCount); sample.put("gcMillis",gcMillis); sample.put("fullGc",fullGc);
                sample.put("players",server.getPlayerList().getPlayers().size()); sample.put("waitingRealChunks",pending.size());
                sample.put("positions",server.getPlayerList().getPlayers().stream().map(player ->
                        Map.of("name",player.getGameProfile().getName(),"dimension",player.serverLevel().dimension().location().toString(),
                                "x",player.getX(),"y",player.getY(),"z",player.getZ())).toList());
                sample.put("cpuNanos",new HashMap<>(cpu)); samples.add(sample);
                write("flight-live.json",samples);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment) -> {
            var root=Commands.literal("flighttest").requires(source -> source.hasPermission(4));
            root.then(Commands.literal("position").executes(ctx -> {
                var server=ctx.getSource().getServer();
                for(var player:server.getPlayerList().getPlayers()) {
                    if(!player.getGameProfile().getName().startsWith("Flight")) continue;
                    boolean second=player.getGameProfile().getName().equals("FlightB");
                    boolean together=System.getProperty("flight.scenario","").equals("together");
                    double x=second&&!together?-16384:16384,z=together&&second?16448:16384;
                    player.teleportTo(server.overworld(),x,180,z,0,0);
                    player.getAbilities().flying=true;player.onUpdateAbilities();
                    System.out.println("FLIGHT_POSITION name="+player.getGameProfile().getName()+" dimension="+player.serverLevel().dimension().location());
                }
                return 1;
            }));
            root.then(Commands.literal("start").executes(ctx -> {
                for(var player:ctx.getSource().getServer().getPlayerList().getPlayers()) {
                    if(player.serverLevel()!=ctx.getSource().getServer().overworld()) {
                        System.out.println("FLIGHT_VALIDATION_FAILURE wrong dimension");return 0;
                    }
                }
                ticks.clear(); waits.clear(); pending.clear(); probeDimensions.clear(); samples.clear(); previousCpu.clear(); cpu.clear();
                start=System.nanoTime(); nextSample=start; measuring=true;
                System.out.println("FLIGHT_START"); return 1;
            }));
            root.then(Commands.literal("finish").executes(ctx -> {
                measuring=false; var result=new LinkedHashMap<String,Object>();
                result.put("ticksMillis",ticks); result.put("readyWaitMillis",waits);
                result.put("probeVersion",2);
                result.put("unresolvedRealProbes",pending.size()); result.put("samples",samples); result.put("cpuNanos",cpu);
                write("flight-result.json",result); System.out.println("FLIGHT_FINISH ticks="+ticks.size()); return 1;
            }));
            dispatcher.register(root);
        });
    }
    private void dropProbes(UUID player) {
        String prefix=player+":";
        pending.keySet().removeIf(key -> key.startsWith(prefix));
        probeDimensions.remove(player);
    }
    /** Give only disposable test accounts the post-intro state of established explorers. */
    private void completeTutorial(net.minecraft.server.level.ServerPlayer player) {
        try {
            Class<?> tutorial=Class.forName("elocindev.prominent.tutorial.PlayerTutorial");
            Class<?> api=Class.forName("elocindev.prominent.api.questing.QuestingAPI");
            Class<?> progress=Class.forName("elocindev.prominent.api.questing.QuestProgress");
            var questId=(net.minecraft.resources.ResourceLocation)tutorial.getField("QUEST_ID").get(null);
            var lookup=java.lang.invoke.MethodHandles.lookup();
            var getter=lookup.findStatic(api,"getProgress",java.lang.invoke.MethodType.methodType(progress,
                    net.minecraft.server.level.ServerPlayer.class,net.minecraft.resources.ResourceLocation.class));
            Object state=getter.invoke(player,questId);
            lookup.findVirtual(progress,"finish",java.lang.invoke.MethodType.methodType(void.class)).invoke(state);
            lookup.findVirtual(progress,"save",java.lang.invoke.MethodType.methodType(void.class)).invoke(state);
            var field=tutorial.getDeclaredField("ADVANCEMENT_REACHING_OVERWORLD");field.setAccessible(true);
            var advancement=player.getServer().getAdvancements().getAdvancement((net.minecraft.resources.ResourceLocation)field.get(null));
            if(advancement!=null) for(String criterion:advancement.getCriteria().keySet()) player.getAdvancements().award(advancement,criterion);
        } catch(ClassNotFoundException optional) { /* Vanilla fixture. */ }
        catch(Throwable error) { throw new IllegalStateException("Cannot initialize disposable explorer progress",error); }
    }
    private void packet(Map<String,Integer> ids,String name,PacketFlow flow,Class<?> type) {
        for(var entry:ConnectionProtocol.PLAY.getPacketsByIds(flow).int2ObjectEntrySet())
            if(entry.getValue()==type) { ids.put(name,entry.getIntKey()); return; }
        throw new IllegalStateException("No packet ID: "+name);
    }
    private void writeOwoLogin() {
        var login=new LinkedHashMap<String,String>();
        try {
            Class<?> handshake=Class.forName("io.wispforest.owo.network.OwoHandshake");
            Class<?> channels=Class.forName("io.wispforest.owo.network.OwoNetChannel");
            Class<?> controllers=Class.forName("io.wispforest.owo.particles.systems.ParticleSystemController");
            var lookup=java.lang.invoke.MethodHandles.privateLookupIn(handshake,java.lang.invoke.MethodHandles.lookup());
            var writer=lookup.findStatic(handshake,"writeHashes",java.lang.invoke.MethodType.methodType(void.class,
                    net.minecraft.network.FriendlyByteBuf.class,Map.class,java.util.function.ToIntFunction.class));
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                for(String fieldName:List.of("REQUIRED_CHANNELS","REGISTERED_CONTROLLERS","OPTIONAL_CHANNELS")) {
                    boolean particles=fieldName.equals("REGISTERED_CONTROLLERS");
                    Class<?> type=particles?controllers:channels;
                    var field=type.getDeclaredField(fieldName); field.setAccessible(true);
                    var hash=lookup.findStatic(handshake,particles?"hashController":"hashChannel",
                            java.lang.invoke.MethodType.methodType(int.class,type));
                    java.util.function.ToIntFunction<Object> function=value -> {
                        try { return (int)hash.invoke(value); }
                        catch(Throwable e) { throw new IllegalStateException(e); }
                    };
                    writer.invoke(buffer,field.get(null),function);
                }
                byte[] bytes=new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
                login.put("owo:handshake",Base64.getEncoder().encodeToString(bytes));
            } finally { buffer.release(); }
        } catch(ClassNotFoundException optional) { /* Vanilla fixture. */ }
        catch(Throwable e) { throw new IllegalStateException("Cannot export local test login schema",e); }
        write("flight-login.json",login);
    }
    private void write(String name,Object value) {
        try { Files.writeString(Path.of(name),gson.toJson(value)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
