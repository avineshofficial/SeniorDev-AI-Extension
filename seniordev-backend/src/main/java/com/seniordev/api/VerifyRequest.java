package com.seniordev.api;

import java.util.List;

public class VerifyRequest {
    private String sessionId;
    private List<String> issueIds;
    private String projectRoot;

    public VerifyRequest() {}

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public List<String> getIssueIds() { return issueIds; }
    public void setIssueIds(List<String> issueIds) { this.issueIds = issueIds; }

    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
}
