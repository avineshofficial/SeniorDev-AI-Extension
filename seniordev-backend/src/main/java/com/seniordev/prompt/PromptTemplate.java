package com.seniordev.prompt;

/**
 * Centralized prompt templates for different AI interaction modes.
 * Extracts prompt construction from OllamaAnalysisEnricher into reusable,
 * testable components.
 */
public class PromptTemplate {

    /**
     * System prompt for generating code fixes.
     * Instructs the AI to produce exact replacement code matching the JSON schema.
     */
    public static final String FIX_SYSTEM_PROMPT =
        "You are an expert code fixer and Staff Software Engineer. " +
        "Output strictly valid JSON with keys `explanation` (1-2 sentences) and `fixCode` (clean, well-structured, modular replacement code). " +
        "If code is unstructured or missing functions, organize it into proper functions and clean logic. " +
        "DO NOT output markdown fences or conversational text.";

    public static final String WHOLE_FILE_FIX_SYSTEM_PROMPT =
        "You are a Staff Principal Software Engineer and Code Fixer. " +
        "Your task is to fix all syntax errors, typos, missing imports, and compile errors in the file so it compiles cleanly with 0 errors.\n\n" +
        "CRITICAL RULES:\n" +
        "- Produce 100% PERFECT, COMPILABLE CODE. Preserve the developer's original logic and structure.\n" +
        "- Fix all typos (e.g., `Scann` -> `Scanner`, `Arralist` -> `ArrayList`, `prntln` -> `println`).\n" +
        "- Fix all punctuation (e.g., colon `:` instead of semicolon `;`, missing brackets, unclosed strings).\n" +
        "- Fix all generic types (e.g., `ArrayList<Book>` instead of raw `ArrayList<>`).\n" +
        "- Ensure required imports are present (e.g., `import java.util.Scanner;`).\n" +
        "- Output the complete file source code from start to end.\n" +
        "- CRITICAL: `fixCode` must ONLY contain valid, executable source code in the target programming language. NEVER put conversational explanations, descriptions, or notes inside `fixCode`.\n" +
        "- IF THE CODE IS ALREADY CORRECT OR HAS NO REAL ERRORS: output the EXACT original file code in `fixCode` unchanged, and set `explanation` to 'Code is already correct — no changes needed.'\n\n" +
        "Output strictly valid JSON with:\n" +
        "- `fixCode`: the complete, 100% correct file source code.\n" +
        "- `explanation`: exactly 1 short sentence (under 15 words) summarizing what was fixed. Never list individual lines or variables.";

    /**
     * System prompt for free-form developer Q&A chat.
     */
    public static final String CHAT_SYSTEM_PROMPT =
        "You are a senior software engineer acting as a code reviewer and mentor. " +
        "Answer the developer's question clearly and concisely. " +
        "If code context is provided, reference it specifically in your answer. " +
        "Focus on practical, actionable advice. " +
        "If you suggest code changes, show the exact replacement code with proper functions and clean structure. " +
        "Do not add unnecessary caveats or disclaimers.";

    /**
     * System prompt for explaining issues without generating fix code.
     */
    public static final String EXPLAIN_SYSTEM_PROMPT =
        "You are a senior developer reviewing a codebase. " +
        "Explain the issue in plain English (2-4 sentences). " +
        "Describe why it matters, what could go wrong if left unfixed, and how to think about the fix. " +
        "Do not output code unless specifically asked. " +
        "Respond only with JSON matching the given schema.";

    /**
     * Builds the user prompt for a code fix request.
     */
    public static String buildFixUserPrompt(String language, String message,
                                             String filePath, int line,
                                             String snippet, String crossFileContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Language: ").append(language).append("\n");
        prompt.append("Issue: ").append(message).append("\n");
        prompt.append("File: ").append(filePath).append(":").append(line).append("\n\n");

        if (crossFileContext != null && !crossFileContext.isEmpty()) {
            prompt.append("Related context from other files:\n");
            prompt.append(crossFileContext).append("\n\n");
        }

        prompt.append("Code snippet:\n");
        prompt.append(snippet != null ? snippet : "No snippet available.");
        prompt.append("\n\nFix ONLY the reported issue. Do NOT restructure or rewrite the rest of the code.");

        return prompt.toString();
    }

    public static String buildWholeFileFixUserPrompt(String language, String filePath, String fileContent, String issuesSummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Language: ").append(language).append("\n");
        prompt.append("File Path: ").append(filePath).append("\n\n");
        if (issuesSummary != null && !issuesSummary.isEmpty()) {
            prompt.append("Known issues to fix:\n").append(issuesSummary).append("\n\n");
        }
        prompt.append("Current code:\n```\n").append(fileContent).append("\n```\n");
        return prompt.toString();
    }

    /**
     * Builds the user prompt for a free-form chat question.
     */
    public static String buildChatUserPrompt(String question, String filePath,
                                              String fileContent, String issueContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Developer question: ").append(question).append("\n");

        if (filePath != null && !filePath.isEmpty()) {
            prompt.append("\nCurrent file: ").append(filePath).append("\n");
        }

        if (fileContent != null && !fileContent.isEmpty()) {
            String truncated = fileContent.length() > 3000
                ? fileContent.substring(0, 3000) + "\n... (truncated)"
                : fileContent;
            prompt.append("\nFile content:\n```\n").append(truncated).append("\n```\n");
        }

        if (issueContext != null && !issueContext.isEmpty()) {
            prompt.append("\nKnown issues in this file:\n").append(issueContext).append("\n");
        }

        return prompt.toString();
    }

    private PromptTemplate() {
        // Utility class — no instantiation
    }
}
