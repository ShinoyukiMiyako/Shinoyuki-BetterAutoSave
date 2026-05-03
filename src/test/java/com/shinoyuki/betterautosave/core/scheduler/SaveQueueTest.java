package com.shinoyuki.betterautosave.core.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveQueueTest {

    @Test
    void priority_sorts_by_deadline_then_proximity_then_sequence() {
        ChunkSavePriority a = new ChunkSavePriority(1L, "overworld", 5L, 200L, 0.0);
        ChunkSavePriority b = new ChunkSavePriority(2L, "overworld", 1L, 100L, 0.9);
        ChunkSavePriority c = new ChunkSavePriority(3L, "overworld", 2L, 100L, 0.1);

        SaveQueue q = new SaveQueue();
        q.offer(a);
        q.offer(b);
        q.offer(c);

        assertEquals(c, q.poll(), "Earliest deadline + lowest proximity wins");
        assertEquals(b, q.poll(), "Same deadline as c but higher proximity loses to c");
        assertEquals(a, q.poll());
        assertNull(q.poll());
    }

    @Test
    void offer_deduplicates_same_chunk_same_dimension() {
        ChunkSavePriority first = new ChunkSavePriority(42L, "overworld", 1L, 100L, 0.0);
        ChunkSavePriority second = new ChunkSavePriority(42L, "overworld", 2L, 100L, 0.0);

        SaveQueue q = new SaveQueue();
        assertTrue(q.offer(first));
        assertFalse(q.offer(second), "Same dim and packedPos must be deduplicated");
        assertEquals(1, q.size());
    }

    @Test
    void same_chunk_in_different_dimension_not_deduplicated() {
        ChunkSavePriority overworld = new ChunkSavePriority(42L, "overworld", 1L, 100L, 0.0);
        ChunkSavePriority nether = new ChunkSavePriority(42L, "nether", 2L, 100L, 0.0);

        SaveQueue q = new SaveQueue();
        assertTrue(q.offer(overworld));
        assertTrue(q.offer(nether), "Different dimension keys are independent");
        assertEquals(2, q.size());
    }

    @Test
    void poll_removes_dedup_marker_so_chunk_can_re_enter() {
        ChunkSavePriority first = new ChunkSavePriority(42L, "overworld", 1L, 100L, 0.0);
        SaveQueue q = new SaveQueue();
        q.offer(first);
        ChunkSavePriority drained = q.poll();
        assertNotNull(drained);

        ChunkSavePriority second = new ChunkSavePriority(42L, "overworld", 5L, 200L, 0.5);
        assertTrue(q.offer(second), "After poll, the same chunk must be eligible to re-enter");
    }

    @Test
    void drainTo_empties_queue_and_dedup_map() {
        SaveQueue q = new SaveQueue();
        for (int i = 0; i < 4; i++) {
            q.offer(new ChunkSavePriority(i, "overworld", i, 100L + i, 0.0));
        }
        List<ChunkSavePriority> sink = new ArrayList<>();
        int n = q.drainTo(sink);
        assertEquals(4, n);
        assertEquals(0, q.size());

        assertTrue(q.offer(new ChunkSavePriority(0, "overworld", 99L, 100L, 0.0)),
                "After drainTo, dedup map should be cleared so previously drained chunks can re-enter");
    }
}
