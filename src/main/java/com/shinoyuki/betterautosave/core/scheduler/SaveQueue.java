package com.shinoyuki.betterautosave.core.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public final class SaveQueue {

    private final PriorityBlockingQueue<ChunkSavePriority> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, ChunkSavePriority> inQueue = new ConcurrentHashMap<>();

    public boolean offer(ChunkSavePriority entry) {
        if (inQueue.putIfAbsent(entry.key(), entry) != null) {
            return false;
        }
        queue.offer(entry);
        return true;
    }

    public ChunkSavePriority poll() {
        ChunkSavePriority entry = queue.poll();
        if (entry != null) {
            inQueue.remove(entry.key(), entry);
        }
        return entry;
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public boolean contains(String key) {
        return inQueue.containsKey(key);
    }

    public int drainTo(Collection<? super ChunkSavePriority> sink) {
        List<ChunkSavePriority> drained = new ArrayList<>(queue.size());
        int n = queue.drainTo(drained);
        for (ChunkSavePriority p : drained) {
            inQueue.remove(p.key(), p);
            sink.add(p);
        }
        return n;
    }

    public void clear() {
        queue.clear();
        inQueue.clear();
    }
}
