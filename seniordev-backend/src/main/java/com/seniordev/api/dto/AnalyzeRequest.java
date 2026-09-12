package com.seniordev.api.dto;

public class AnalyzeRequest {
    private String projectRoot;
    private String targetFilePath;

    public AnalyzeRequest() {}

    public AnalyzeRequest(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public AnalyzeRequest(String projectRoot, String targetFilePath) {
        this.projectRoot = projectRoot;
        this.targetFilePath = targetFilePath;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String getTargetFilePath() {
        return targetFilePath;
    }

    public void setTargetFilePath(String targetFilePath) {
        this.targetFilePath = targetFilePath;
    }
}
