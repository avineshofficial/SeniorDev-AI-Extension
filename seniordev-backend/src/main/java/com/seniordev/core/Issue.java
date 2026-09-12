package com.seniordev.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Issue {

    private String id = UUID.randomUUID().toString();
    private String language;
    private IssueType type;
    private Severity severity;
    private String filePath;
    private int line;
    private String message;
    private String snippet;

    // AI-populated fields (Phase 3+)
    private String suggestion;
    private String aiExplanation;
    private String aiFixCode;

    // Verification fields (Phase 4+)
    private VerificationStatus verificationStatus = VerificationStatus.NOT_VERIFIED;
    private List<String> relatedFiles = new ArrayList<>();
    private String verificationDetail;

    public Issue() {}

    public Issue(String language, IssueType type, Severity severity,
                 String filePath, int line, String message) {
        this.language = language;
        this.type = type;
        this.severity = severity;
        this.filePath = filePath;
        this.line = line;
        this.message = message;
    }

    // --- Getters and Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public IssueType getType() { return type; }
    public void setType(IssueType type) { this.type = type; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getAiExplanation() { return aiExplanation; }
    public void setAiExplanation(String aiExplanation) { this.aiExplanation = aiExplanation; }

    public String getAiFixCode() { return aiFixCode; }
    public void setAiFixCode(String aiFixCode) { this.aiFixCode = aiFixCode; }

    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(VerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }

    public List<String> getRelatedFiles() { return relatedFiles; }
    public void setRelatedFiles(List<String> relatedFiles) { this.relatedFiles = relatedFiles; }

    public String getVerificationDetail() { return verificationDetail; }
    public void setVerificationDetail(String verificationDetail) { this.verificationDetail = verificationDetail; }
}
