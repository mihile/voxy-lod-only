package dev.mihile.voxylodonly.benchmark;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.function.LongConsumer;

/** Reflection keeps this same instrumentation JAR usable in plain Chunky without Voxy. */
public class BenchmarkMod implements ModInitializer {
    private String fence;
    private int quietTicks;
    private long lastSample;
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("lodbench").requires(source -> source.hasPermission(4))
                .then(Commands.literal("fence").then(Commands.argument("label", StringArgumentType.word()).executes(ctx -> {
                    fence = StringArgumentType.getString(ctx, "label"); quietTicks = 0; return 1;
                })))
                .then(Commands.literal("sample").executes(ctx -> { sample(ctx.getSource().getServer()); return 1; }));
            root.then(Commands.literal("compact").executes(ctx -> {
                try { compact(ctx.getSource().getServer()); return 1; }
                catch (Exception e) { throw new IllegalStateException(e); }
            }));
            root.then(Commands.literal("livebytes").executes(ctx -> {
                try { liveBytes(ctx.getSource().getServer()); return 1; }
                catch (Exception e) { throw new IllegalStateException(e); }
            }));
            for (String type : new String[]{"world", "lod"}) root.then(Commands.literal("load" + type)
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0,128)).executes(ctx -> {
                        try {
                            load(ctx.getSource().getServer(), type, IntegerArgumentType.getInteger(ctx,"x"),
                                    IntegerArgumentType.getInteger(ctx,"z"), IntegerArgumentType.getInteger(ctx,"radius"));
                            return 1;
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    })))));
            dispatcher.register(root);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            if (now - lastSample >= 1_000_000_000L) { lastSample = now; sample(server); }
            if (fence == null) return;
            try {
                Object engine = engine();
                if (engine != null && (taskCount(ingest(engine)) != 0 || taskCount(saver(engine)) != 0)) {
                    quietTicks = 0; return;
                }
                if (++quietTicks < 5) return;
                long start = System.nanoTime();
                server.saveEverything(true, true, true);
                long[] positions = {0};
                if (engine != null) {
                    Object world = engine.getClass().getMethod("getOrCreate", ServerLevel.class).invoke(engine, server.overworld());
                    world.getClass().getMethod("acquireRef").invoke(world);
                    try {
                        Object storage = world.getClass().getField("storage").get(world);
                        var flush = storage.getClass().getMethod("flush"); flush.setAccessible(true); flush.invoke(storage);
                        var iterate = storage.getClass().getMethod("iteratePositions", int.class, LongConsumer.class); iterate.setAccessible(true);
                        iterate.invoke(storage, 0, (LongConsumer)key -> positions[0]++);
                    } finally { world.getClass().getMethod("releaseRef").invoke(world); }
                }
                LoggerFactory.getLogger("lod_benchmark").info("BENCH_FENCE label={} flush_ms={} lod_positions={}",
                        fence, (System.nanoTime() - start) / 1e6, positions[0]);
                fence = null;
            } catch (Exception e) {
                LoggerFactory.getLogger("lod_benchmark").error("BENCH_FAILURE", e); fence = null;
            }
        });
    }
    private static Object engine() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("voxyserver")) return null;
        Object bridge = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                .filter(instance -> instance.getClass().getName().equals("com.dripps.voxyserver.Voxyserver")).findFirst().orElseThrow();
        Field field = bridge.getClass().getDeclaredField("lodEngine"); field.setAccessible(true); return field.get(bridge);
    }
    private static Object saver(Object engine) throws Exception {
        Field field = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance").getDeclaredField("savingService");
        field.setAccessible(true); return field.get(engine);
    }
    private static Object ingest(Object engine) throws Exception { return engine.getClass().getMethod("getIngestService").invoke(engine); }
    private static int taskCount(Object service) throws Exception { return (Integer)service.getClass().getMethod("getTaskCount").invoke(service); }
    private static Object field(Object instance,String name) throws Exception {
        for (Class<?> type=instance.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(instance); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static Object rocks(MinecraftServer server) throws Exception {
        Object engine=engine();
        Object world=engine.getClass().getMethod("getOrCreate",ServerLevel.class).invoke(engine,server.overworld());
        Object storage=world.getClass().getField("storage").get(world);
        for (int i=0;i<8;i++) {
            if (storage.getClass().getName().endsWith("RocksDBStorageBackend")) return storage;
            try {storage=field(storage,"delegate");}catch (NoSuchFieldException ignored) {storage=field(storage,"backend");}
        }
        throw new IllegalStateException("RocksDB backend not found");
    }
    private static void liveBytes(MinecraftServer server) throws Exception {
        Object backend=rocks(server), db=field(backend,"db");
        Class<?> handleType=Class.forName("org.rocksdb.ColumnFamilyHandle");
        long total=0, target=0, targetAllLevels=0, count=0;
        for (String name:new String[]{"worldSections","idMappings"}) {
            Object iterator=db.getClass().getMethod("newIterator",handleType).invoke(db,field(backend,name));
            var cl=iterator.getClass();
            try {
                cl.getMethod("seekToFirst").invoke(iterator);
                while ((Boolean)cl.getMethod("isValid").invoke(iterator)) {
                    byte[] key=(byte[])cl.getMethod("key").invoke(iterator), value=(byte[])cl.getMethod("value").invoke(iterator);
                    total+=key.length+value.length;count++;
                    if (name.equals("worldSections") && key.length==8) {
                        long id=java.nio.ByteBuffer.wrap(key).getLong();
                        int level=(int)(id>>>60),x=(int)((id<<36)>>40),z=(int)((id<<12)>>40);
                        // Main benchmark's level-0 footprint; boundary sections cover up to one extra chunk.
                        if (level==0 && x>=60 && x<=68 && z>=60 && z<=68) target+=key.length+value.length;
                        int span=1<<(level+1);
                        if (x>=Math.floorDiv(120,span) && x<=Math.floorDiv(136,span)
                                && z>=Math.floorDiv(120,span) && z<=Math.floorDiv(136,span)) targetAllLevels+=key.length+value.length;
                    }
                    cl.getMethod("next").invoke(iterator);
                }
            } finally {cl.getMethod("close").invoke(iterator);}
        }
        LoggerFactory.getLogger("lod_benchmark").info("BENCH_LIVE total_compressed_key_value_bytes={} target_level0_bytes={} target_all_levels_bytes={} records={}",total,target,targetAllLevels,count);
    }
    private static void compact(MinecraftServer server) throws Exception {
        Object backend=rocks(server),db=field(backend,"db");
        Class<?> handleType=Class.forName("org.rocksdb.ColumnFamilyHandle"), optionsType=Class.forName("org.rocksdb.FlushOptions");
        Object options=optionsType.getConstructor().newInstance();optionsType.getMethod("setWaitForFlush",boolean.class).invoke(options,true);
        var handles=java.util.List.of(field(backend,"worldSections"),field(backend,"idMappings"));
        long start=System.nanoTime();
        try {db.getClass().getMethod("flush",optionsType,java.util.List.class).invoke(db,options,handles);}
        finally {optionsType.getMethod("close").invoke(options);}
        for (Object handle:handles) db.getClass().getMethod("compactRange",handleType).invoke(db,handle);
        LoggerFactory.getLogger("lod_benchmark").info("BENCH_COMPACT elapsed_ms={}",(System.nanoTime()-start)/1e6);
    }
    private static void load(MinecraftServer server, String type, int x, int z, int radius) throws Exception {
        long started = System.nanoTime(), units = 0, fingerprint = 0;
        if (type.equals("world")) {
            for (int cz=z-radius;cz<=z+radius;cz++) for (int cx=x-radius;cx<=x+radius;cx++) {
                var chunk = server.overworld().getChunk(cx,cz);
                fingerprint += chunk.getSections().length; units++;
            }
        } else {
            Object engine=engine();
            Object world=engine.getClass().getMethod("getOrCreate",ServerLevel.class).invoke(engine,server.overworld());
            var acquire=world.getClass().getMethod("acquireIfExists",int.class,int.class,int.class,int.class);
            world.getClass().getMethod("acquireRef").invoke(world);
            try {
                for (int cz=Math.floorDiv(z-radius,2);cz<=Math.floorDiv(z+radius,2);cz++)
                    for (int cx=Math.floorDiv(x-radius,2);cx<=Math.floorDiv(x+radius,2);cx++)
                        for (int cy=server.overworld().getMinSection()>>1;cy<=((server.overworld().getMaxSection()-1)>>1);cy++) {
                            Object section=acquire.invoke(world,0,cx,cy,cz);
                            if (section==null)continue;
                            try {
                                long[] data=(long[])section.getClass().getMethod("_unsafeGetRawDataArray").invoke(section);
                                fingerprint ^= data[0]; units++;
                            } finally { section.getClass().getMethod("release").invoke(section); }
                        }
            } finally { world.getClass().getMethod("releaseRef").invoke(world); }
        }
        LoggerFactory.getLogger("lod_benchmark").info("BENCH_LOAD type={} elapsed_ms={} units={} fingerprint={}",
                type,(System.nanoTime()-started)/1e6,units,fingerprint);
        sample(server);
    }
    private static void sample(MinecraftServer server) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long count = 0, time = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount()); time += Math.max(0, gc.getCollectionTime());
        }
        LoggerFactory.getLogger("lod_benchmark").info("BENCH_SAMPLE heap_used={} heap_committed={} gc_count={} gc_ms={} mspt={} processors={}",
                heap.getUsed(), heap.getCommitted(), count, time, server.getAverageTickTime(), Runtime.getRuntime().availableProcessors());
    }
}
