package dev.mihile.voxylodonly;

import org.junit.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import static org.junit.Assert.*;

public class StructureCompatibilityTest {
    private CompoundTag terminal() {
        var tag=new CompoundTag();tag.putString("id","minecraft:jigsaw");tag.putString("pool","minecraft:");
        tag.putString("target","minecraft:");tag.putString("final_state","minecraft:air");tag.putString("name","betterarcheology:walkway_entrance");return tag;
    }
    @Test public void onlyConfirmedTerminalSignatureChangesAndIsIdempotent() {
        var id=new ResourceLocation("betterarcheology:catacombs/catacombs_endpiece_1");
        var tag=terminal();var expected=tag.copy();expected.putString("pool","minecraft:empty");
        assertTrue(StructureCompatibility.repairTerminal(id,tag));assertEquals(expected,tag);
        assertFalse(StructureCompatibility.repairTerminal(id,tag));
        tag=terminal();tag.putString("final_state","minecraft:iron_bars[east=true,west=true]");
        expected=tag.copy();expected.putString("pool","minecraft:empty");
        assertTrue(StructureCompatibility.repairTerminal(id,tag));assertEquals(expected,tag);
    }
    @Test public void unrelatedOrRealConnectionsStayUntouched() {
        var tag=terminal();var unchanged=tag.copy();
        assertFalse(StructureCompatibility.repairTerminal(new ResourceLocation("other:catacombs/room"),tag));assertEquals(unchanged,tag);
        var id=new ResourceLocation("betterarcheology:catacombs/room");
        tag.putString("target","betterarcheology:entrance");assertFalse(StructureCompatibility.repairTerminal(id,tag));
        tag=terminal();tag.putString("pool","betterarcheology:catacombs/rooms");assertFalse(StructureCompatibility.repairTerminal(id,tag));
    }
    @Test public void onlyAbsentKnownOptionalEntitiesAreRemoved() {
        var template=new ResourceLocation("integrated_villages:airship_village/ship/cargo");
        var tag=new CompoundTag();tag.putString("id","quark:glass_frame");
        assertTrue(StructureCompatibility.missingOptional(template,tag,id -> false));
        assertFalse(StructureCompatibility.missingOptional(template,tag,id -> true));
        assertFalse(StructureCompatibility.missingOptional(new ResourceLocation("other:template"),tag,id -> false));
        tag.putString("id","minecraft:pig");assertFalse(StructureCompatibility.missingOptional(template,tag,id -> false));
        tag.putString("id","other:unknown");assertFalse(StructureCompatibility.missingOptional(template,tag,id -> false));
    }
}
