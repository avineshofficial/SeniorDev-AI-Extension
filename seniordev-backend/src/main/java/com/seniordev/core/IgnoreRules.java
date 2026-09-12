package com.seniordev.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filters files/directories that should be excluded from scanning.
 * Reads .gitignore if present and applies hardcoded default excludes.
 */
public class IgnoreRules {

    private static final List<String> DEFAULT_IGNORES = List.of(
        "node_modules", "target", "build", "dist", ".git",
        "venv", ".venv", "__pycache__", ".idea", ".vscode",
        ".gradle", "bin", "out", ".settings", ".classpath", ".project"
    );

    private final List<PathMatcher> matchers = new ArrayList<>();
    private final Path projectRoot;

    public IgnoreRules(Path projectRoot) {
        this.projectRoot = projectRoot;

        // Hardcoded defaults â€” match directory names anywhere in the tree
        for (String pattern : DEFAULT_IGNORES) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + pattern));
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + pattern + "/**"));
        }

        // Parse .gitignore if present
        Path gitignore = projectRoot.resolve(".gitignore");
        if (Files.isRegularFile(gitignore)) {
            try {
                List<String> lines = Files.readAllLines(gitignore);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                    // Remove trailing slashes for directory patterns
                    if (trimmed.endsWith("/")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }

                    try {
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + trimmed));
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + trimmed + "/**"));
                    } catch (Exception e) {
                        // Skip malformed patterns
                    }
                }
            } catch (IOException e) {
                // .gitignore unreadable â€” proceed with defaults only
            }
        }
    }

    /**
     * Returns true if the given path should be ignored (excluded from scanning).
     */
    public boolean shouldIgnore(Path path) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
