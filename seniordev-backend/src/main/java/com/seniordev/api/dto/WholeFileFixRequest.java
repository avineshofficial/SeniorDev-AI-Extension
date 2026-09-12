package com.seniordev.api.dto;

import com.seniordev.core.Issue;
import java.util.List;

public class WholeFileFixRequest {
    private String sessionId;
    private String filePath;
    private String fileContent;
    private String language;
    private List<Issue> issues;

    public WholeFileFixRequest() {}

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public List<Issue> getIssues() { return issues; }
    public void setIssues(List<Issue> issues) { this.issues = issues; }
}
