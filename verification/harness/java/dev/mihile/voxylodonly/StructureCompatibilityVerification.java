package dev.mihile.voxylodonly;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.*;
import java.lang.reflect.Method;
import org.slf4j.LoggerFactory;

public final class StructureCompatibilityVerification {
    private static void require(boolean value,String message) { if (!value) throw new IllegalStateException(message); }
    private static StructureTemplate entityTemplate(ServerLevel level,String... ids) {
        var tag=new CompoundTag();var size=new ListTag();for (int n:new int[]{1,1,1}) size.add(IntTag.valueOf(n));tag.put("size",size);
        var palette=new ListTag();var air=new CompoundTag();air.putString("Name","minecraft:air");palette.add(air);tag.put("palette",palette);
        var blocks=new ListTag();var block=new CompoundTag();block.putInt("state",0);var bp=new ListTag();for(int n:new int[]{0,0,0})bp.add(IntTag.valueOf(n));block.put("pos",bp);blocks.add(block);tag.put("blocks",blocks);var entities=new ListTag();
        for (String id:ids) {
            var info=new CompoundTag();var nbt=new CompoundTag();nbt.putString("id",id);info.put("nbt",nbt);
            var pos=new ListTag();for (double d:new double[]{0.5,0.0,0.5}) pos.add(DoubleTag.valueOf(d));info.put("pos",pos);
            var blockPos=new ListTag();for (int n:new int[]{0,0,0}) blockPos.add(IntTag.valueOf(n));info.put("blockPos",blockPos);entities.add(info);
        }
        tag.put("entities",entities);return level.getStructureManager().readStructure(tag);
    }
    public static void run(ServerLevel level) throws Exception {
        verifyResources(level);
        var placement=new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings();
        var origin=new BlockPos(0,200,0);
        Class<?>[] types={ServerLevelAccessor.class,BlockPos.class,Mirror.class,Rotation.class,BlockPos.class,BoundingBox.class,boolean.class};
        // MixinExtras retains renamed inner originals with the same arguments.
        // Call the exposed method so the verification exercises all wrappers.
        Method privatePlacement=Arrays.stream(StructureTemplate.class.getDeclaredMethods())
                .filter(m -> !m.getName().contains("$") && Arrays.equals(m.getParameterTypes(),types)).findFirst().orElseThrow();
        privatePlacement.setAccessible(true);
        var protectedTemplate=entityTemplate(level,"minecraft:pig","quark:glass_frame","guardvillagers:guard");
        var entityTest=net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Entity.class);
        Set<net.minecraft.world.entity.Entity> before=new HashSet<>(level.getEntities(entityTest,e -> true));
        long skips=StructureCompatibility.lodEntitiesSkipped.get();
        try (var guard=GenerationGuard.enter()) {
            privatePlacement.invoke(protectedTemplate,level,origin,Mirror.NONE,Rotation.NONE,BlockPos.ZERO,null,false);
        }
        require(StructureCompatibility.lodEntitiesSkipped.get()==skips+3,"Private placement hook did not skip all entities");
        Set<net.minecraft.world.entity.Entity> after=new HashSet<>(level.getEntities(entityTest,e -> true));
        require(after.equals(before),"Private template spawned a real entity");
        // Positive control: ordinary template entity placement is still enabled outside the guard.
        entityTemplate(level,"minecraft:pig").placeInWorld(level,origin,origin,placement,net.minecraft.util.RandomSource.create(0),2);
        after.clear();after.addAll(level.getEntities(entityTest,e -> true));after.removeAll(before);
        require(after.size()==1 && after.iterator().next() instanceof net.minecraft.world.entity.animal.Pig,"Normal template entity was suppressed");
        after.forEach(net.minecraft.world.entity.Entity::discard);
        LoggerFactory.getLogger("lod_only_verification").info("VERIFY_STRUCTURE_ENTITIES privateSkipped=3 ordinaryPig=1 PASS");

    }
    private static void verifyResources(ServerLevel level) throws Exception {
        // Call the existing resource source directly, outside our enclosing tryLoad
        // hook. This retains ModernFix's actual DFU/cache semantics in the control.
        var sourceField=Arrays.stream(level.getStructureManager().getClass().getDeclaredFields())
                .filter(field -> field.getType()==List.class).findFirst().orElseThrow();
        sourceField.setAccessible(true);
        var sources=(List<?>)sourceField.get(level.getStructureManager());
        var resourceSource=sources.get(sources.size()-1);
        var loaderMethod=Arrays.stream(resourceSource.getClass().getDeclaredMethods())
                .filter(method -> method.getParameterCount()==0 && method.getReturnType()==java.util.function.Function.class).findFirst().orElseThrow();
        loaderMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        var originalLoader=(java.util.function.Function<ResourceLocation,Optional<StructureTemplate>>)loaderMethod.invoke(resourceSource);
        var resources=level.getServer().getResourceManager().listResources("structures",id -> id.getPath().endsWith(".nbt")
                && (id.getNamespace().equals("betterarcheology") || id.getNamespace().equals("integrated_villages")));
        int checked=0,repairs=0,removed=0;
        for (var entry:resources.entrySet()) {
            var resourceId=entry.getKey();var id=new ResourceLocation(resourceId.getNamespace(),resourceId.getPath().substring(11,resourceId.getPath().length()-4));
            StructureTemplate original=originalLoader.apply(id).orElseThrow();
            // Placement processors in this pack can mutate cached chest NBT.
            // Compare freshly loaded resources rather than previously placed templates.
            level.getStructureManager().remove(id);
            var loaded=level.getStructureManager().get(id).orElseThrow();
            var a=(TemplateAccess)original;var b=(TemplateAccess)loaded;
            require(a.lodOnly$palettes().size()==b.lodOnly$palettes().size(),"Changed palette count "+id);
            for (int p=0;p<a.lodOnly$palettes().size();p++) {
                var left=a.lodOnly$palettes().get(p).blocks();var right=b.lodOnly$palettes().get(p).blocks();
                require(left.size()==right.size(),"Changed block count "+id);
                for (int i=0;i<left.size();i++) {
                    var x=left.get(i);var y=right.get(i);require(x.pos().equals(y.pos()) && x.state()==y.state(),"Changed terrain block "+id);
                    var expected=x.nbt()==null?null:x.nbt().copy();
                    if (x.state().is(Blocks.JIGSAW) && StructureCompatibility.repairTerminal(id,expected)) repairs++;
                    require(Objects.equals(expected,y.nbt()),"Unexpected NBT mutation "+id+" pos="+x.pos()+" expected="+expected+" actual="+y.nbt());
                }
            }
            var expected=new ArrayList<>(a.lodOnly$entities());
            int size=expected.size();expected.removeIf(e -> StructureCompatibility.missingOptional(id,e.nbt,net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE::containsKey));removed+=size-expected.size();
            require(expected.size()==b.lodOnly$entities().size(),"Wrong entity count "+id);
            for (int i=0;i<expected.size();i++) {
                var x=expected.get(i);var y=b.lodOnly$entities().get(i);
                require(x.pos.equals(y.pos) && x.blockPos.equals(y.blockPos) && x.nbt.equals(y.nbt),"Changed valid entity "+id);
            }
            checked++;
        }
        LoggerFactory.getLogger("lod_only_verification").info("VERIFY_STRUCTURE_RESOURCES templates={} terminalRepairs={} missingOptionalRemoved={} exactBlocksAndOtherNBT PASS",checked,repairs,removed);
    }
}
