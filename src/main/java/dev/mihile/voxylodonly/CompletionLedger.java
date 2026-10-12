package dev.mihile.voxylodonly;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Committed only after VoxyServer has flushed on a clean server shutdown. */
public final class CompletionLedger {
    private static final int MAGIC = 0x564C4F31;
    private static final int LIMIT = 4_000_000;
    private final Set<Long> completed = new HashSet<>();
    private final Path file;
    private final long seed;
    public CompletionLedger(Path file, long seed) throws IOException {
        this.file = file; this.seed = seed;
        if (Files.exists(file)) {
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != MAGIC) throw new IOException("Unknown checkpoint format");
                if (in.readLong() != seed) return;
                int count = in.readInt();
                if (count < 0 || count > LIMIT || Files.size(file) != 16L + count * 8L) throw new IOException("Malformed checkpoint");
                for (int i = 0; i < count; i++) completed.add(in.readLong());
            }
        }
    }
    public boolean contains(long pos) { return completed.contains(pos); }
    public void add(long pos) { if (completed.size() >= LIMIT && !completed.contains(pos)) throw new IllegalStateException("Checkpoint capacity reached"); completed.add(pos); }
    public int size() { return completed.size(); }
    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var out = new DataOutputStream(Files.newOutputStream(temp))) {
            out.writeInt(MAGIC); out.writeLong(seed); out.writeInt(completed.size());
            for (long pos : completed.stream().sorted().toList()) out.writeLong(pos);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
                break;
            } catch (AccessDeniedException e) {
                // Windows can temporarily hold the just-written file. Keep the previous
                // checkpoint intact; never delete it to force an overwrite.
                if (attempt >= 5) throw e;
                try { Thread.sleep(20L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException("Interrupted checkpoint replacement", interrupted);
                }
            }
        }
    }
}
