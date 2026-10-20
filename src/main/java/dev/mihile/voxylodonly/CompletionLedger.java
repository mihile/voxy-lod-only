package dev.mihile.voxylodonly;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Committed only after VoxyServer has flushed on a clean server shutdown. */
public final class CompletionLedger {
    private static final int MAGIC = 0x564C4F31;
    private static final int LIMIT = 4_000_000;
    private final LongOpenHashSet completed;
    private final Path file;
    private final long seed;
    public CompletionLedger(Path file, long seed) throws IOException {
        this.file = file; this.seed = seed;
        if (Files.exists(file)) {
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != MAGIC) throw new IOException("Unknown checkpoint format");
                if (in.readLong() != seed) { this.completed = new LongOpenHashSet(); return; }
                int count = in.readInt();
                if (count < 0 || count > LIMIT || Files.size(file) != 16L + count * 8L) throw new IOException("Malformed checkpoint");
                this.completed = new LongOpenHashSet(Math.max(16, count));
                for (int i = 0; i < count; i++) completed.add(in.readLong());
            }
        } else {
            this.completed = new LongOpenHashSet();
        }
    }
    public boolean contains(long pos) { return completed.contains(pos); }
    public void add(long pos) { if (completed.size() >= LIMIT && !completed.contains(pos)) throw new IllegalStateException("Checkpoint capacity reached"); completed.add(pos); }
    public void remove(long pos) { completed.remove(pos); }
    public int size() { return completed.size(); }
    public boolean needsSave() { return !completed.isEmpty() || Files.exists(file); }
    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        long[] positions = completed.toLongArray();
        Arrays.sort(positions);
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC); out.writeLong(seed); out.writeInt(positions.length);
            for (long pos : positions) out.writeLong(pos);
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
