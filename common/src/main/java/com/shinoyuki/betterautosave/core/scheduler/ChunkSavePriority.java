package com.shinoyuki.betterautosave.core.scheduler;

public record ChunkSavePriority(
        long packedPos,
        String dimensionId,
        long enqueueSequence,
        long deadlineMillis,
        double playerProximityScore
) implements Comparable<ChunkSavePriority> {

    @Override
    public int compareTo(ChunkSavePriority other) {
        int cmp = Long.compare(this.deadlineMillis, other.deadlineMillis);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Double.compare(this.playerProximityScore, other.playerProximityScore);
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(this.enqueueSequence, other.enqueueSequence);
    }

    public String key() {
        return dimensionId + ":" + packedPos;
    }
}
