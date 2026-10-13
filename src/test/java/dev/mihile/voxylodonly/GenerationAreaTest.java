package dev.mihile.voxylodonly;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GenerationAreaTest {
    @Test public void zeroRadiusAndNegativeBlockCoordinatesSelectContainingChunk() {
        for (var shape:GenerationArea.Shape.values()) {
            var area=new GenerationArea(-1,-17,shape,0);
            assertTrue(area.includes(-1,-2)); assertFalse(area.includes(0,-2));
            assertEquals(1,area.totalChunks());
        }
    }
    @Test public void circleIncludesEdgeIntersectionButRejectsSquareCorner() {
        var circle=new GenerationArea(0,0,GenerationArea.Shape.circle,16);
        var square=new GenerationArea(0,0,GenerationArea.Shape.square,16);
        assertTrue(circle.includes(1,0)); assertTrue(circle.includes(-1,-1));
        assertFalse(circle.includes(1,1)); assertTrue(square.includes(1,1));
        assertEquals(9,square.totalChunks()); assertEquals(6,circle.totalChunks());
    }
    @Test public void optimizedCircleCountMatchesBruteForceAcrossBlockOffsets() {
        for (int center:new int[]{-33,-17,-1,0,7,15,16,31}) for (int radius:new int[]{0,1,15,16,17,31,64}) {
            var area=new GenerationArea(center,7-center,GenerationArea.Shape.circle,radius);
            long count=0;
            for (int x=area.minX();x<=area.maxX();x++) for (int z=area.minZ();z<=area.maxZ();z++) if (area.includes(x,z)) count++;
            assertEquals(count,area.totalChunks());
        }
    }
    @Test public void tileTraversalCoversBothShapesExactlyOnceForEveryTileSize() {
        for (var shape:GenerationArea.Shape.values()) for (int size:new int[]{4,8,16,32}) for (boolean origin:new boolean[]{false,true}) {
            var area=new GenerationArea(-17,31,shape,129);
            var plan=new TilePlan(area,size,origin); Set<Long> visited=new HashSet<>();
            while (plan.hasNext()) {
                var tile=plan.next();
                for (int x=plan.startX(tile);x<plan.startX(tile)+size;x++) for (int z=plan.startZ(tile);z<plan.startZ(tile)+size;z++)
                    if (plan.includes(x,z)) assertTrue(visited.add(((long)x<<32)^(z&0xffffffffL)));
            }
            assertEquals(area.totalChunks(),visited.size());
            assertThrows(NoSuchElementException.class,plan::next);
        }
    }
    @Test public void maximumAreaIsBoundedAndStartsInCenterTile() {
        var area=new GenerationArea(0,0,GenerationArea.Shape.square,8192);
        assertEquals(1_050_625,area.totalChunks());
        var plan=new TilePlan(area,4,false);
        assertEquals(new TilePlan.Tile(0,0),plan.next());
        var edge=new GenerationArea(29_999_999,-29_999_999,GenerationArea.Shape.circle,8192);
        assertTrue(edge.totalChunks()>0); assertEquals(1_874_999,edge.maxX());
    }
    @Test public void invalidRadiusOrCenterFailsBeforeWorkIsScheduled() {
        assertThrows(IllegalArgumentException.class,() -> new GenerationArea(0,0,GenerationArea.Shape.circle,8193));
        assertThrows(IllegalArgumentException.class,() -> new GenerationArea(Integer.MIN_VALUE,0,GenerationArea.Shape.square,1));
    }
}
