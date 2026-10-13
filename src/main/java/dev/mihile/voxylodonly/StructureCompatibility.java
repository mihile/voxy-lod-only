package dev.mihile.voxylodonly;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** Correct known resource defects before publishing a template; never edits files or filters logs. */
public final class StructureCompatibility {
    public static final AtomicLong terminalPoolsRepaired=new AtomicLong();
    public static final AtomicLong optionalEntitiesRemoved=new AtomicLong();
    public static final AtomicLong lodEntitiesSkipped=new AtomicLong();
    private static final Set<String> OPTIONAL=Set.of("quark:glass_frame","guardvillagers:guard");
    static boolean terminalResource(ResourceLocation id) {
        return id.getNamespace().equals("betterarcheology")
                && (id.getPath().startsWith("catacombs/") || id.getPath().startsWith("light_temple/"));
    }
    public static boolean repairTerminal(ResourceLocation id,CompoundTag tag) {
        if (!terminalResource(id) || tag==null || !tag.getString("id").equals("minecraft:jigsaw")
                || !tag.getString("pool").equals("minecraft:") || !tag.getString("target").equals("minecraft:")) return false;
        tag.putString("pool","minecraft:empty"); return true;
    }
    public static boolean missingOptional(ResourceLocation template,CompoundTag entity,Predicate<ResourceLocation> registered) {
        if (!template.getNamespace().equals("integrated_villages")) return false;
        String id=entity.getString("id");
        return OPTIONAL.contains(id) && !registered.test(new ResourceLocation(id));
    }
    public static void repair(ResourceLocation id,StructureTemplate template) {
        TemplateAccess access=(TemplateAccess)template;
        if (terminalResource(id)) for (var palette:access.lodOnly$palettes()) for (var block:palette.blocks()) {
            if (block.state().is(Blocks.JIGSAW) && repairTerminal(id,block.nbt())) terminalPoolsRepaired.incrementAndGet();
        }
        if (id.getNamespace().equals("integrated_villages")) {
            int before=access.lodOnly$entities().size();
            access.lodOnly$entities().removeIf(entity -> missingOptional(id,entity.nbt,BuiltInRegistries.ENTITY_TYPE::containsKey));
            optionalEntitiesRemoved.addAndGet(before-access.lodOnly$entities().size());
        }
    }
    private StructureCompatibility() {}
}
