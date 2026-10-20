package dev.mihile.voxylodonly;

import java.util.*;

/** Per-session cursors, served one tile at a time by a single global pipeline. */
final class AutomaticRequests {
    static final long BASE_RETRY_BACKOFF_NANOS = 1_000_000_000L;
    record Viewer(UUID id, Object session, String dimension, int x, int z) {}
    record DeferredRetry(int tileX, int tileZ, int attempts, long retryAtNanos) {
        TilePlan.Tile tile() { return new TilePlan.Tile(tileX, tileZ); }
    }
    static final class Entry {
        final Viewer viewer;
        final TilePlan plan;
        final int tileSize;
        final int innerRadius;
        final long created;
        int lastX, lastZ, vx, vz;
        int lastAbortedTileX = Integer.MIN_VALUE, lastAbortedTileZ = Integer.MIN_VALUE, consecutiveAborts;
        long served, nextScan;
        DeferredRetry retry;
        Entry(Viewer viewer,int radius,int tileSize,long now) {
            this(viewer,radius,tileSize,0,0,0,now);
        }
        Entry(Viewer viewer,int radius,int tileSize,int innerRadius,int vx,int vz,long now) {
            this.viewer=viewer; this.tileSize=tileSize; this.innerRadius=innerRadius;
            this.lastX=viewer.x(); this.lastZ=viewer.z();
            this.vx=vx; this.vz=vz; created=now;
            plan=new TilePlan(viewer.x(),viewer.z(),radius,tileSize,
                    viewer.dimension().equals("minecraft:overworld"),innerRadius,vx,vz);
        }
    }
    record Selection(Entry entry,TilePlan.Tile tile) {}
    private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
    private UUID last;
    private int radius=-1, size=-1;
    private long retargets, retryBackoffs;
    void update(List<Viewer> viewers,int radius,int tileSize,int moveThreshold,long now) {
        update(viewers,radius,tileSize,moveThreshold,0,now);
    }
    void update(List<Viewer> viewers,int radius,int tileSize,int moveThreshold,int innerRadius,long now) {
        if (this.radius!=radius || size!=tileSize) { entries.clear(); this.radius=radius; size=tileSize; }
        Set<UUID> present=new HashSet<>();
        for (var viewer:viewers) {
            present.add(viewer.id());
            var old=entries.get(viewer.id());
            if (old==null || old.viewer.session()!=viewer.session() || !old.viewer.dimension().equals(viewer.dimension())) {
                if(old!=null) retargets++;
                entries.put(viewer.id(),new Entry(viewer,radius,tileSize,innerRadius,0,0,now));
            } else {
                long anchorDx=(long)viewer.x()-old.viewer.x(), anchorDz=(long)viewer.z()-old.viewer.z();
                long stepDx=(long)viewer.x()-old.lastX, stepDz=(long)viewer.z()-old.lastZ;
                old.lastX=viewer.x(); old.lastZ=viewer.z();
                if (stepDx!=0 || stepDz!=0) {
                    old.vx=(int)Math.max(-128,Math.min(128,stepDx));
                    old.vz=(int)Math.max(-128,Math.min(128,stepDz));
                }
                if (Math.max(Math.abs(anchorDx),Math.abs(anchorDz))>=moveThreshold) {
                    retargets++;
                    entries.put(viewer.id(),new Entry(viewer,radius,tileSize,innerRadius,old.vx,old.vz,now));
                } else if (stepDx!=0 || stepDz!=0) {
                    old.plan.setDirection(old.vx,old.vz);
                }
            }
        }
        entries.keySet().retainAll(present);
    }
    boolean current(Entry entry) { return entries.get(entry.viewer.id())==entry; }
    Entry entry(UUID id) { return entries.get(id); }
    Selection next(long now) {
        var candidates=new ArrayList<>(entries.values());
        int start=0;
        for(int i=0;i<candidates.size();i++) if(candidates.get(i).viewer.id().equals(last)) start=i+1;
        for(int i=0;i<candidates.size();i++) {
            var entry=candidates.get((start+i)%candidates.size());
            if (now<entry.nextScan) continue;
            if (entry.retry!=null) {
                if (now<entry.retry.retryAtNanos()) continue;
                var tile=entry.retry.tile(); entry.retry=null;
                entry.served++; last=entry.viewer.id(); return new Selection(entry,tile);
            }
            if(entry.plan.hasNext()) {
                var tile=entry.plan.next();
                if (tile.x()!=entry.lastAbortedTileX || tile.z()!=entry.lastAbortedTileZ) entry.consecutiveAborts=0;
                entry.served++; last=entry.viewer.id(); return new Selection(entry,tile);
            }
            // Revisit stationary areas lazily; do not rescan 66k positions every tick.
            if(entry.nextScan!=0) {
                entries.put(entry.viewer.id(),new Entry(entry.viewer,radius,entry.tileSize,entry.innerRadius,entry.vx,entry.vz,now));
            } else entry.nextScan=now+5_000_000_000L;
        }
        return null;
    }
    Selection advanceSameEntry(Selection selection, long now) {
        var entry=selection.entry();
        if (!current(entry)) return null;
        if (entry.retry!=null) {
            if (now<entry.retry.retryAtNanos()) return null;
            var tile=entry.retry.tile(); entry.retry=null;
            return new Selection(entry,tile);
        }
        if (entry.plan.hasNext()) {
            var tile=entry.plan.next();
            if (tile.x()!=entry.lastAbortedTileX || tile.z()!=entry.lastAbortedTileZ) entry.consecutiveAborts=0;
            return new Selection(entry,tile);
        }
        if (entry.nextScan==0) entry.nextScan=now+5_000_000_000L;
        return null;
    }
    void retry(Selection selection) { retry(selection, 0L); }
    void retry(Selection selection, long now) {
        var entry=selection.entry();
        if (!current(entry)) return;
        var tile=selection.tile();
        int attempts = (entry.lastAbortedTileX==tile.x() && entry.lastAbortedTileZ==tile.z())
                ? entry.consecutiveAborts+1 : 1;
        entry.lastAbortedTileX=tile.x();
        entry.lastAbortedTileZ=tile.z();
        entry.consecutiveAborts=attempts;
        long delay = now<=0L ? 0L : (BASE_RETRY_BACKOFF_NANOS << Math.min(attempts-1, 5));
        entry.retry=new DeferredRetry(tile.x(),tile.z(),attempts,now+delay);
        if (delay>0L) retryBackoffs++;
    }
    void clear() { entries.clear(); last=null; }
    String status(long now) {
        var rows=new ArrayList<String>();
        for(var e:entries.values()) rows.add(e.viewer.id()+":"+e.served+":"+(now-e.created)/1_000_000L+"ms");
        return "players="+entries.size()+", retargets="+retargets+", retryBackoffs="+retryBackoffs+", playerService={"+String.join(";",rows)+"}";
    }
}
