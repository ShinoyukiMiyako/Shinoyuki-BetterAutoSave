package com.shinoyuki.betterautosave.core.scheduler;

public interface ChunkSubmissionSink {

    void submitChunk(ChunkSavePriority priority);
}
