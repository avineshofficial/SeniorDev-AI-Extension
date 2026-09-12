package com.seniordev.api.dto;

/**
 * Response DTO for the free-form developer chat endpoint.
 */
public class ChatResponse {
    private String answer;
    private String model;

    public ChatResponse() {}

    public ChatResponse(String answer, String model) {
        this.answer = answer;
        this.model = model;
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
