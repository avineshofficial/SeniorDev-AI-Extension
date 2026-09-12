package com.seniordev.api.dto;

import java.util.List;

public class WholeFileFixResponse {
    private String explanation;
    private String fixedContent;
    private int issuesFixed;
    private List<ErrorDetail> errorsFound;

    public static class ErrorDetail {
        private int line;
        private String message;
        public ErrorDetail() {}
        public ErrorDetail(int line, String message) { this.line = line; this.message = message; }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public WholeFileFixResponse() {}

    public WholeFileFixResponse(String explanation, String fixedContent, int issuesFixed, List<ErrorDetail> errorsFound) {
        this.explanation = explanation;
        this.fixedContent = fixedContent;
        this.issuesFixed = issuesFixed;
        this.errorsFound = errorsFound;
    }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getFixedContent() { return fixedContent; }
    public void setFixedContent(String fixedContent) { this.fixedContent = fixedContent; }

    public int getIssuesFixed() { return issuesFixed; }
    public void setIssuesFixed(int issuesFixed) { this.issuesFixed = issuesFixed; }

    public List<ErrorDetail> getErrorsFound() { return errorsFound; }
    public void setErrorsFound(List<ErrorDetail> errorsFound) { this.errorsFound = errorsFound; }
}
