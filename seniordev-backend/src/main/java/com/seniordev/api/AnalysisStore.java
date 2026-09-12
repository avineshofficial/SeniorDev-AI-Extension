package com.seniordev.api;

import com.seniordev.core.Issue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store for analysis results.
 * Keyed by session ID (generated per analysis run). No DB needed for v1.
 */
@Component
public class AnalysisStore {

    private final ConcurrentHashMap<String, List<Issue>> store = new ConcurrentHashMap<>();

    public void put(String sessionId, List<Issue> issues) {
        store.put(sessionId, issues);
    }

    public List<Issue> get(String sessionId) {
        return store.getOrDefault(sessionId, List.of());
    }

    public boolean has(String sessionId) {
        return store.containsKey(sessionId);
    }

    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
