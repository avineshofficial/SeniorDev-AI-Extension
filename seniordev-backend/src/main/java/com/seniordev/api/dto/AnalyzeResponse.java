package com.seniordev.api.dto;

import com.seniordev.core.Issue;

import java.util.List;

public class AnalyzeResponse {

    private String sessionId;
    private String projectRoot;
    private int totalIssues;
    private List<Issue> issues;
    private String buildSystem;
    private boolean classpathResolved;

    public AnalyzeResponse() {}

    public AnalyzeResponse(String sessionId, String projectRoot, List<Issue> issues,
                            String buildSystem, boolean classpathResolved) {
        this.sessionId = sessionId;
        this.projectRoot = projectRoot;
        this.totalIssues = issues.size();
        this.issues = issues;
        this.buildSystem = buildSystem;
        this.classpathResolved = classpathResolved;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }

    public int getTotalIssues() { return totalIssues; }
    public void setTotalIssues(int totalIssues) { this.totalIssues = totalIssues; }

    public List<Issue> getIssues() { return issues; }
    public void setIssues(List<Issue> issues) { this.issues = issues; }

    public String getBuildSystem() { return buildSystem; }
    public void setBuildSystem(String buildSystem) { this.buildSystem = buildSystem; }

    public boolean isClasspathResolved() { return classpathResolved; }
    public void setClasspathResolved(boolean classpathResolved) { this.classpathResolved = classpathResolved; }
}
