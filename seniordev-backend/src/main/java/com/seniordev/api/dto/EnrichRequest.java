package com.seniordev.api.dto;

import java.util.List;

public class EnrichRequest {
    private String sessionId;
    private List<String> issueIds;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getIssueIds() {
        return issueIds;
    }

    public void setIssueIds(List<String> issueIds) {
        this.issueIds = issueIds;
    }
}
