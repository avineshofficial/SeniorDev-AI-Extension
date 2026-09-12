package com.seniordev.ai;

import com.seniordev.core.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for AI-generated FixResults.
 * Keyed by a hash of (issueMessage + snippet + filePath) so that
 * identical issues across repeated scans skip the Ollama call entirely.
 * 
 * No TTL eviction in v1 — cache persists for the JVM lifetime.
 * Future: add TTL-based eviction via a scheduled task.
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    private final ConcurrentHashMap<String, FixResult> cache = new ConcurrentHashMap<>();

    /**
     * Generates a cache key from an issue's identifying properties.
     */
    public String keyFor(Issue issue) {
        String raw = (issue.getMessage() != null ? issue.getMessage() : "") + "|" +
                     (issue.getSnippet() != null ? issue.getSnippet() : "") + "|" +
                     (issue.getFilePath() != null ? issue.getFilePath() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in the JDK
            return String.valueOf(raw.hashCode());
        }
    }

    /**
     * Returns a cached FixResult if available, or null if not cached.
     */
    public FixResult get(Issue issue) {
        String key = keyFor(issue);
        FixResult cached = cache.get(key);
        if (cached != null) {
            log.debug("Cache HIT for issue: {}", issue.getMessage());
        }
        return cached;
    }

    /**
     * Stores a FixResult in the cache.
     */
    public void put(Issue issue, FixResult result) {
        if (result != null) {
            String key = keyFor(issue);
            cache.put(key, result);
            log.debug("Cached fix for issue: {} (cache size: {})", issue.getMessage(), cache.size());
        }
    }

    /**
     * Clears the entire cache.
     */
    public void clear() {
        cache.clear();
        log.info("Response cache cleared");
    }

    /**
     * Returns the current cache size.
     */
    public int size() {
        return cache.size();
    }
}
