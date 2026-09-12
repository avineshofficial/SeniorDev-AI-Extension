package com.seniordev.api.dto;

/**
 * Request DTO for the free-form developer chat endpoint.
 */
public class ChatRequest {
    private String question;
    private String sessionId;
    private String filePath;
    private String fileContent;

    public ChatRequest() {}

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }
}
