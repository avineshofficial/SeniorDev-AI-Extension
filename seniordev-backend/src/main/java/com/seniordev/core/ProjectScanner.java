package com.seniordev.core;

import com.seniordev.build.BuildSystem;
import com.seniordev.build.ClasspathResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Scans a project directory to discover source files and build context.
 * Respects IgnoreRules and detects the build system from marker files.
 */
@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final ClasspathResolver classpathResolver;

    public ProjectScanner(ClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
    }

    public BuildContext scan(Path projectRoot) {
        return scan(projectRoot, null);
    }

    /**
     * Scans the project and returns a fully populated BuildContext.
     */
    public BuildContext scan(Path projectRoot, Path targetFilePath) {
        log.info("Scanning project at: {} (target: {})", projectRoot, targetFilePath);

        BuildSystem buildSystem = detectBuildSystem(projectRoot);
        log.info("Detected build system: {}", buildSystem);

        List<Path> sourceFiles;
        if (targetFilePath != null && Files.isRegularFile(targetFilePath)) {
            sourceFiles = List.of(targetFilePath);
            log.info("Single file target specified: {}", targetFilePath);
        } else {
            IgnoreRules ignoreRules = new IgnoreRules(projectRoot);
            sourceFiles = discoverSourceFiles(projectRoot, ignoreRules);
            log.info("Discovered {} source files", sourceFiles.size());
        }

        BuildContext ctx = new BuildContext(projectRoot, buildSystem);
        ctx.setTargetFilePath(targetFilePath);
        ctx.setSourceFiles(sourceFiles);

        // Resolve classpath for Maven projects
        if (buildSystem == BuildSystem.MAVEN) {
            String classpath = classpathResolver.resolve(projectRoot);
            ctx.setResolvedClasspath(classpath);
        }

        return ctx;
    }

    /**
     * Detects the build system from marker files in the project root.
     */
    BuildSystem detectBuildSystem(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle")) ||
            Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        if (Files.isRegularFile(projectRoot.resolve("package.json"))) {
            return BuildSystem.NPM;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * Recursively discovers source files (.java, .py, .ts, .js) in the project,
     * filtering out ignored paths with max depth = 6 to avoid hanging on system/home directories.
     */
    private List<Path> discoverSourceFiles(Path projectRoot, IgnoreRules ignoreRules) {
        List<Path> sources = new ArrayList<>();

        try {
            Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.startsWith(".") || dirName.equals("Library") || dirName.equals("node_modules") ||
                        dirName.equals("target") || dirName.equals("build") || dirName.equals("dist") ||
                        dirName.equals("venv") || dirName.equals(".venv") || ignoreRules.shouldIgnore(dir)) {
                        log.debug("Ignoring directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (ignoreRules.shouldIgnore(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".java") || name.endsWith(".py") ||
                        name.endsWith(".ts") || name.endsWith(".js")) {
                        sources.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("Failed to visit file: {} ({})", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error scanning project directory: {}", e.getMessage());
        }

        return sources;
    }
}
