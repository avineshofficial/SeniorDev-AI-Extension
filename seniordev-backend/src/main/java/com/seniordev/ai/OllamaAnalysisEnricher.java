package com.seniordev.ai;

import com.seniordev.core.Issue;
import com.seniordev.prompt.ContextBuilder;
import com.seniordev.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Enriches detected issues with AI-generated explanations and fix code.
 * Uses PromptTemplate for consistent prompts, ContextBuilder for cross-file
 * symbol context, and ResponseCache to skip redundant Ollama calls.
 */
@Service
public class OllamaAnalysisEnricher {

    private static final Logger log = LoggerFactory.getLogger(OllamaAnalysisEnricher.class);

    private final OllamaClient ollamaClient;
    private final ResponseCache responseCache;
    private final ContextBuilder contextBuilder;

    public OllamaAnalysisEnricher(OllamaClient ollamaClient, ResponseCache responseCache) {
        this.ollamaClient = ollamaClient;
        this.responseCache = responseCache;
        this.contextBuilder = new ContextBuilder();
    }

    /**
     * Enriches a list of issues with AI explanations and fix code.
     * Skips already-enriched issues and uses cache for repeated issues.
     */
    public void enrich(List<Issue> issues) {
        enrich(issues, null);
    }

    /**
     * Enriches a list of issues with AI explanations and fix code,
     * optionally including cross-file context from the project.
     *
     * @param issues      the issues to enrich
     * @param projectRoot the project root for cross-file context (nullable)
     */
    public void enrich(List<Issue> issues, Path projectRoot) {
        for (Issue issue : issues) {
            if (issue.getAiExplanation() != null && !issue.getAiExplanation().isEmpty()) {
                continue; // Already enriched
            }

            // Check cache first
            FixResult cached = responseCache.get(issue);
            if (cached != null) {
                applyFixResult(issue, cached);
                log.info("Used cached fix for issue: {} (line {})", issue.getMessage(), issue.getLine());
                continue;
            }

            long startTime = System.currentTimeMillis();

            // Build cross-file context if project root is available
            String crossFileContext = "";
            if (projectRoot != null) {
                try {
                    crossFileContext = contextBuilder.buildContext(issue, projectRoot);
                } catch (Exception e) {
                    log.debug("Cross-file context lookup failed: {}", e.getMessage());
                }
            }

            String userPrompt = PromptTemplate.buildFixUserPrompt(
                issue.getLanguage(),
                issue.getMessage(),
                issue.getFilePath(),
                issue.getLine(),
                issue.getSnippet(),
                crossFileContext
            );

            FixResult result = ollamaClient.generateFix(PromptTemplate.FIX_SYSTEM_PROMPT, userPrompt);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AI fix generated in {}ms for issue: {} (line {})",
                elapsed, issue.getMessage(), issue.getLine());

            if (result != null) {
                applyFixResult(issue, result);
                responseCache.put(issue, result);
            }
        }
    }

    public FixResult fixWholeFile(String language, String filePath, String fileContent, List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        if (issues != null) {
            for (int i = 0; i < issues.size(); i++) {
                Issue issue = issues.get(i);
                sb.append(i + 1).append(". Line ").append(issue.getLine()).append(": ").append(issue.getMessage()).append("\n");
            }
        }
        String prompt = PromptTemplate.buildWholeFileFixUserPrompt(language, filePath, fileContent, sb.toString());
        FixResult result = ollamaClient.generateWholeFileFix(PromptTemplate.WHOLE_FILE_FIX_SYSTEM_PROMPT, prompt);

        if (result != null) {
            String fixCode = result.fixCode();
            if (fixCode != null && isLikelyExplanation(fixCode, language)) {
                log.info("AI returned explanation text in fixCode: '{}'. Preserving original file code.",
                    fixCode.length() > 80 ? fixCode.substring(0, 80) + "..." : fixCode);
                String explanation = (result.explanation() != null && !result.explanation().isBlank() && !result.explanation().equals(fixCode))
                    ? result.explanation()
                    : fixCode;
                return new FixResult(explanation, fileContent, List.of(), "NONE", List.of());
            }
        }
        return result;
    }

    public static boolean isLikelyExplanation(String text, String language) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();

        // Common explanation starter phrases returned by LLMs
        if (lower.startsWith("the code is") ||
            lower.startsWith("this code is") ||
            lower.startsWith("the provided code") ||
            lower.startsWith("no changes") ||
            lower.startsWith("already free of") ||
            lower.startsWith("i have reviewed") ||
            lower.startsWith("there are no") ||
            lower.startsWith("all issues") ||
            lower.startsWith("the only issue") ||
            lower.startsWith("note:") ||
            lower.contains("already free of syntax errors") ||
            lower.contains("is not applicable in java") ||
            lower.contains("no changes needed") ||
            lower.contains("no changes are needed")) {
            return true;
        }

        // Structural check: C-style languages (Java, etc.)
        String lang = language != null ? language.toLowerCase() : "";
        if ("java".equals(lang) || "c".equals(lang) || "cpp".equals(lang)) {
            boolean hasBraces = trimmed.contains("{") && trimmed.contains("}");
            boolean hasSemicolons = trimmed.contains(";");
            boolean hasClassKeyword = trimmed.matches("(?s).*\\b(class|interface|enum|record|package|public|import)\\b.*");
            if (!hasBraces && !hasSemicolons && !hasClassKeyword) {
                return true; // Not valid Java code!
            }
        } else if ("python".equals(lang)) {
            boolean hasPyConstructs = trimmed.matches("(?s).*\\b(def|class|import|from|for|while|if|return|print)\\b.*") || trimmed.contains("=");
            if (!hasPyConstructs) {
                return true; // Not valid Python code!
            }
        }

        return false;
    }

    /**
     * Handles a free-form chat question from the developer.
     *
     * @param question    the developer's question
     * @param filePath    optional current file path for context
     * @param fileContent optional current file content for context
     * @param issueContext optional related issues summary
     * @return the AI's response text
     */
    public String chat(String question, String filePath, String fileContent, String issueContext) {
        long startTime = System.currentTimeMillis();

        String userPrompt = PromptTemplate.buildChatUserPrompt(
            question, filePath, fileContent, issueContext
        );

        try {
            // For chat, we don't want structured JSON output — just plain text
            var response = ollamaClient.generateChatResponse(
                PromptTemplate.CHAT_SYSTEM_PROMPT, userPrompt
            );

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Chat response generated in {}ms for question: {}",
                elapsed, question.length() > 80 ? question.substring(0, 80) + "..." : question);

            return response;
        } catch (Exception e) {
            log.error("Chat response generation failed: {}", e.getMessage());
            return "Sorry, I couldn't generate a response. Error: " + e.getMessage();
        }
    }

    private void applyFixResult(Issue issue, FixResult result) {
        issue.setAiExplanation(result.explanation());
        issue.setAiFixCode(result.fixCode());
        if (result.affectedFiles() != null) {
            issue.setRelatedFiles(result.affectedFiles());
        }
    }
}
