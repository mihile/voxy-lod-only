package dev.mihile.voxylodonly;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.List;

public interface TemplateAccess {
    List<StructureTemplate.Palette> lodOnly$palettes();
    List<StructureTemplate.StructureEntityInfo> lodOnly$entities();
}
