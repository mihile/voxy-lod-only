package dev.mihile.voxylodonly;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ConfigReloadTest {
    @Test public void radiusSavePreservesOtherDiskEditsAndUnknownProperties() throws Exception {
        Path file=Files.createTempDirectory("lod-live-config").resolve("config.json");
        Files.writeString(file,"{\"radiusChunks\":32,\"generationTileSize\":8,\"withPlayers\":{\"enabled\":false},\"futureSetting\":123}");
        LodOnlyConfig.saveRadius(file,64);
        var loaded=LodOnlyConfig.load(file);
        assertEquals(64,loaded.radiusChunks);assertEquals(8,loaded.generationTileSize);
        assertFalse(loaded.policy(true).enabled());
        assertTrue(Files.readString(file).contains("\"futureSetting\": 123"));
    }
    @Test public void invalidConfigAndInvalidRadiusCannotOverwriteDisk() throws Exception {
        Path file=Files.createTempDirectory("lod-invalid-config").resolve("config.json");
        for (String original:new String[]{"{\"generationTileSize\":3}","[]","{bad"}) {
            Files.writeString(file,original);
            assertThrows(RuntimeException.class,() -> LodOnlyConfig.saveRadius(file,64));
            assertEquals(original,Files.readString(file));
            assertThrows(IllegalStateException.class,() -> LodOnlyConfig.load(file));
        }
        Files.writeString(file,"{\"radiusChunks\":32}");
        assertThrows(IllegalArgumentException.class,() -> LodOnlyConfig.saveRadius(file,129));
        assertEquals("{\"radiusChunks\":32}",Files.readString(file));
    }
    @Test public void radiusSnapshotCannotMutatePreviousWorkerConfiguration() {
        var original=new LodOnlyConfig();original.withPlayers.generationThreads=1;
        var next=original.withRadius(64);next.withPlayers.generationThreads=4;
        assertEquals(32,original.radiusChunks);assertEquals(1,original.policy(true).generationThreads());
        assertEquals(64,next.radiusChunks);assertEquals(4,(int)next.withPlayers.generationThreads);
        assertEquals(2,next.policy(true).generationThreads());
    }
    @Test public void invalidatedModeCheckpointRemainsInvalidAfterRestart() throws Exception {
        Path file=Files.createTempDirectory("lod-mode-checkpoint").resolve("done.bin");
        var ledger=new CompletionLedger(file,42);assertFalse(ledger.needsSave());
        ledger.add(123);ledger.save();ledger.remove(123);
        assertTrue(ledger.needsSave());ledger.save();
        assertFalse(new CompletionLedger(file,42).contains(123));
    }
}
