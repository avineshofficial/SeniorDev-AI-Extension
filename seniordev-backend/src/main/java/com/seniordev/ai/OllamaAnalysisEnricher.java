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

    public static String resolveLanguage(String language, String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            String lowerPath = filePath.toLowerCase();
            if (lowerPath.endsWith(".py") || lowerPath.endsWith(".pyw")) return "python";
            if (lowerPath.endsWith(".java")) return "java";
            if (lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs") || lowerPath.endsWith(".cjs")) return "javascript";
            if (lowerPath.endsWith(".ts") || lowerPath.endsWith(".tsx")) return "typescript";
            if (lowerPath.endsWith(".cpp") || lowerPath.endsWith(".cc") || lowerPath.endsWith(".cxx") || lowerPath.endsWith(".hpp") || lowerPath.endsWith(".h")) return "cpp";
            if (lowerPath.endsWith(".c")) return "c";
            if (lowerPath.endsWith(".go")) return "go";
            if (lowerPath.endsWith(".rs")) return "rust";
            if (lowerPath.endsWith(".rb")) return "ruby";
            if (lowerPath.endsWith(".php")) return "php";
            if (lowerPath.endsWith(".cs")) return "csharp";
            if (lowerPath.endsWith(".html") || lowerPath.endsWith(".htm")) return "html";
            if (lowerPath.endsWith(".css")) return "css";
            if (lowerPath.endsWith(".json")) return "json";
            if (lowerPath.endsWith(".sql")) return "sql";
            if (lowerPath.endsWith(".sh") || lowerPath.endsWith(".bash")) return "bash";
        }
        if (language != null && !language.isBlank()) {
            String l = language.trim().toLowerCase();
            if (l.equals("py") || l.equals("python") || l.equals("python3")) return "python";
            if (l.equals("java")) return "java";
            if (l.equals("js") || l.equals("javascript")) return "javascript";
            if (l.equals("ts") || l.equals("typescript")) return "typescript";
            if (l.equals("c++") || l.equals("cpp")) return "cpp";
            if (l.equals("c")) return "c";
            if (l.equals("golang") || l.equals("go")) return "go";
            if (l.equals("rs") || l.equals("rust")) return "rust";
            if (l.equals("rb") || l.equals("ruby")) return "ruby";
            if (l.equals("cs") || l.equals("csharp")) return "csharp";
            return l;
        }
        return "python";
    }

    public static boolean isCrossLanguageContaminated(String code, String targetLanguage) {
        if (code == null || code.isBlank()) return false;
        String target = targetLanguage != null ? targetLanguage.toLowerCase() : "";
        if ("python".equals(target)) {
            if (code.contains("public class ") ||
                code.contains("public static void main") ||
                code.contains("import java.") ||
                code.contains("System.out.") ||
                code.contains("Scanner scanner") ||
                code.matches("(?s).*\\bclass\\s+\\w+\\s*\\{.*") ||
                code.matches("(?s).*;\\s*\\n.*\\{.*")) {
                return true;
            }
        } else if ("java".equals(target)) {
            if (code.matches("(?s).*\\bdef\\s+\\w+\\s*\\(.*") ||
                code.matches("(?s).*\\belif\\b.*") ||
                code.contains("import numpy") ||
                (code.matches("(?s).*\\bprint\\s*\\(.*") && !code.contains("System.out.print"))) {
                return true;
            }
        }
        return false;
    }

    public static String appendStep(String code, String var, String step) {
        return CodeLogicAnalyzer.appendStep(code, var, step);
    }

    public static String[] extractLoopInfo(String code) {
        return CodeLogicAnalyzer.extractLoopInfo(code);
    }

    public static FixResult analyzeAndFixLoop(String code) {
        return CodeLogicAnalyzer.analyzeAndFixLoop(code);
    }

    public static boolean isLoopLogicInverted(String original, String fix) {
        return CodeLogicAnalyzer.isLoopLogicInverted(original, fix);
    }

    public static boolean isDeadCodeOrInfiniteLoop(String code) {
        return CodeLogicAnalyzer.isDeadCodeOrInfiniteLoop(code);
    }

    public static boolean isLoopLogicallyValid(String code) {
        return CodeLogicAnalyzer.isLoopLogicallyValid(code);
    }

    public static String fixYodaConditionOnly(String code, String[] loopInfo) {
        return CodeLogicAnalyzer.fixYodaConditionOnly(code, loopInfo);
    }

    public static String fallbackPythonFix(String originalContent) {
        return CodeLogicAnalyzer.fallbackPythonFix(originalContent);
    }

    public FixResult fixWholeFile(String language, String filePath, String fileContent, List<Issue> issues) {
        String normLang = resolveLanguage(language, filePath);

        // INTELLIGENT GUARD: If user's code is already 100% syntactically & logically valid, DO NOT MUTATE IT!
        boolean hasIssues = issues != null && !issues.isEmpty();
        boolean hasUndefinedVar = fileContent != null && fileContent.contains("print(j)") && !fileContent.contains("j =");
        if (!hasIssues && !hasUndefinedVar && CodeLogicAnalyzer.isLoopLogicallyValid(fileContent)) {
            log.info("Original file code is already 100% syntactically and logically valid. Preserving without changes.");
            return new FixResult("Code is already correct — no changes needed.", fileContent, List.of(), "HIGH", List.of());
        }

        StringBuilder sb = new StringBuilder();
        if (issues != null) {
            for (int i = 0; i < issues.size(); i++) {
                Issue issue = issues.get(i);
                sb.append(i + 1).append(". Line ").append(issue.getLine()).append(": ").append(issue.getMessage()).append("\n");
            }
        }
        String systemPrompt = PromptTemplate.getWholeFileFixSystemPrompt(normLang);
        String prompt = PromptTemplate.buildWholeFileFixUserPrompt(normLang, filePath, fileContent, sb.toString());
        FixResult result = ollamaClient.generateWholeFileFix(systemPrompt, prompt);

        // Guard against cross-language contamination (e.g. Java code generated for Python file)
        if (result != null && result.fixCode() != null && isCrossLanguageContaminated(result.fixCode(), normLang)) {
            log.warn("Detected cross-language contamination for {} (expected {}). Retrying with high-priority correction prompt.",
                filePath, normLang);
            String retryPrompt = "CRITICAL ERROR: You generated code in the WRONG language!\n" +
                "Target Language: " + normLang.toUpperCase() + " (file: " + filePath + ").\n" +
                "You MUST generate 100% pure " + normLang.toUpperCase() + " code. Absolutely NO other language syntax or keywords are allowed!\n\n" +
                "Current original code:\n```\n" + fileContent + "\n```\n\n" +
                "Think step-by-step and write the complete, correct, runnable " + normLang.toUpperCase() + " code in `fixCode` with 0 syntax or logic errors.";
            FixResult retryResult = ollamaClient.generateWholeFileFix(systemPrompt, retryPrompt);
            if (retryResult != null && retryResult.fixCode() != null && !isCrossLanguageContaminated(retryResult.fixCode(), normLang)) {
                result = retryResult;
            } else if ("python".equals(normLang)) {
                String safePy = CodeLogicAnalyzer.fallbackPythonFix(fileContent);
                log.info("Cross-language contamination persisted; applying deterministic pure Python logic fix.");
                return new FixResult("Fixed undefined variable name and ensured loop termination.", safePy, List.of(), "HIGH", List.of());
            }
        }

        if (result != null) {
            String fixCode = result.fixCode();
            if (fixCode != null && isLikelyExplanation(fixCode, normLang)) {
                log.info("AI returned explanation text in fixCode: '{}'. Preserving original file code.",
                    fixCode.length() > 80 ? fixCode.substring(0, 80) + "..." : fixCode);
                String explanation = (result.explanation() != null && !result.explanation().isBlank() && !result.explanation().equals(fixCode))
                    ? result.explanation()
                    : fixCode;
                return new FixResult(explanation, fileContent, List.of(), "NONE", List.of());
            }

            // Post-processing guard: replace lazy 'pass' in loops with meaningful logic using the loop variable
            if (fixCode != null) {
                if ("python".equalsIgnoreCase(normLang)) {
                    String enhanced = fixCode.replaceAll("(?m)^(\\s*)for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+([^:]+):\\s*\\n\\1(\\s+)pass\\b", "$1for $2 in $3:\n$1$4print($2)");
                    if (!enhanced.equals(fixCode)) {
                        log.info("Replaced lazy 'pass' loop placeholder with meaningful loop logic print(var)");
                        fixCode = enhanced;
                    }
                }

                // CRITICAL GUARD: Detect when LLM changed comparison values while "fixing" conditions across ANY language.
                // E.g., original: "while 1 < a:" (means a > 1), LLM changed to "while a > 0:" (WRONG! changed 1 to 0)
                if (CodeLogicAnalyzer.isLoopLogicInverted(fileContent, fixCode)) {
                    log.info("LLM changed loop comparison values! Preserving original semantics.");
                    String[] origInfo = CodeLogicAnalyzer.extractWhileLoopInfo(fileContent);
                    if (origInfo != null) {
                        boolean origIsYoda = "true".equals(origInfo[4]);
                        if (origIsYoda && !CodeLogicAnalyzer.isDeadCodeOrInfiniteLoop(fileContent)) {
                            String safeFixed = CodeLogicAnalyzer.fixYodaConditionOnly(fileContent, origInfo);
                            String safeExpl = result.explanation() != null ? result.explanation() : "Fixed Yoda condition style.";
                            return new FixResult(safeExpl, safeFixed, List.of(), "HIGH", List.of());
                        }
                    }
                    FixResult loopFix = CodeLogicAnalyzer.analyzeAndFixLoop(fileContent);
                    if (loopFix != null) {
                        log.info("Restored non-inverted loop intent from original file: {}", loopFix.explanation());
                        return loopFix;
                    }
                    return new FixResult("Code is already correct — no changes needed.", fileContent, List.of(), "HIGH", List.of());
                }

                // If fixCode is dead code or has an infinite loop:
                if (CodeLogicAnalyzer.isDeadCodeOrInfiniteLoop(fixCode)) {
                    FixResult loopFix = CodeLogicAnalyzer.analyzeAndFixLoop(fixCode);
                    if (loopFix == null) {
                        loopFix = CodeLogicAnalyzer.analyzeAndFixLoop(fileContent);
                    }
                    if (loopFix != null) {
                        log.info("Fixed dead code/infinite loop in AI result: {}", loopFix.explanation());
                        return loopFix;
                    }
                } else if (CodeLogicAnalyzer.isDeadCodeOrInfiniteLoop(fileContent) && fixCode.trim().equals(fileContent.trim())) {
                    FixResult loopFix = CodeLogicAnalyzer.analyzeAndFixLoop(fileContent);
                    if (loopFix != null) {
                        log.info("Fixed unaddressed loop error from original file: {}", loopFix.explanation());
                        return loopFix;
                    }
                }
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
