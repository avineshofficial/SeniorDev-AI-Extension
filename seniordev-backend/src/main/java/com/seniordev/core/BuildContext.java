package com.seniordev.core;

import com.seniordev.build.BuildSystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BuildContext {

    private Path projectRoot;
    private BuildSystem buildSystem;
    private String resolvedClasspath = "";
    private List<Path> sourceFiles = new ArrayList<>();
    private Path compiledClassesDir;
    private Path targetFilePath;

    public BuildContext() {}

    public BuildContext(Path projectRoot, BuildSystem buildSystem) {
        this.projectRoot = projectRoot;
        this.buildSystem = buildSystem;
    }

    public Path getProjectRoot() { return projectRoot; }
    public void setProjectRoot(Path projectRoot) { this.projectRoot = projectRoot; }

    public Path getTargetFilePath() { return targetFilePath; }
    public void setTargetFilePath(Path targetFilePath) { this.targetFilePath = targetFilePath; }

    public BuildSystem getBuildSystem() { return buildSystem; }
    public void setBuildSystem(BuildSystem buildSystem) { this.buildSystem = buildSystem; }

    public String getResolvedClasspath() { return resolvedClasspath; }
    public void setResolvedClasspath(String resolvedClasspath) { this.resolvedClasspath = resolvedClasspath; }

    public List<Path> getSourceFiles() { return sourceFiles; }
    public void setSourceFiles(List<Path> sourceFiles) { this.sourceFiles = sourceFiles; }

    public Path getCompiledClassesDir() { return compiledClassesDir; }
    public void setCompiledClassesDir(Path compiledClassesDir) { this.compiledClassesDir = compiledClassesDir; }
}

