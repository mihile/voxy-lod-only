package dev.mihile.voxylodonly.mixin;

import dev.mihile.voxylodonly.GenerationGuard;
import dev.mihile.voxylodonly.StructureCompatibility;
import dev.mihile.voxylodonly.TemplateAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.List;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements TemplateAccess {
    @Shadow @Final private List<StructureTemplate.Palette> palettes;
    @Shadow @Final private List<StructureTemplate.StructureEntityInfo> entityInfoList;
    @Override public List<StructureTemplate.Palette> lodOnly$palettes() { return palettes; }
    @Override public List<StructureTemplate.StructureEntityInfo> lodOnly$entities() { return entityInfoList; }
    // Wrap the whole method, including other mods' HEAD entity processors.
    @WrapMethod(method="placeEntities")
    private void lodOnly$skipPreviewEntities(ServerLevelAccessor level,BlockPos origin,Mirror mirror,Rotation rotation,
            BlockPos pivot,BoundingBox bounds,boolean finalizeEntities,Operation<Void> original) {
        if (GenerationGuard.active()) {
            StructureCompatibility.lodEntitiesSkipped.addAndGet(entityInfoList.size());
            return;
        }
        original.call(level,origin,mirror,rotation,pivot,bounds,finalizeEntities);
    }
}
