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

    public static String getWholeFileFixSystemPrompt(String language) {
        String lang = (language != null ? language : "").toLowerCase();
        if (lang.contains("py")) {
            return "You are a Staff Principal Python Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: PYTHON (.py file).\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID PYTHON CODE.\n" +
                   "NEVER output Java, C++, or any other programming language. NEVER use Java keywords like `public class`, `import java.util`, or semicolons `;`.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, COMPILABLE, MEANINGFUL PYTHON CODE. Preserve the developer's original intent.\n" +
                   "- Fix undefined names (e.g., if `j` is used but `a` was initialized, correct `print(j)` to `print(a)`).\n" +
                   "- RESOLVE LOGICAL BUGS: Ensure loops terminate properly (e.g., `while a > 0:` must decrement `a -= 1` so it doesn't run forever).\n" +
                   "- NEVER output lazy placeholder statements like `pass`, `// TODO`, or empty blocks.\n" +
                   "- Output strictly valid JSON with:\n" +
                   "  - `fixCode`: the complete, 100% correct Python file source code.\n" +
                   "  - `explanation`: exactly 1 short sentence (under 15 words) summarizing what was fixed.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT (both syntax AND logic): output the EXACT original file code in `fixCode` unchanged, and set `explanation` to 'Code is already correct — no changes needed.'";
        } else if (lang.contains("java")) {
            return "You are a Staff Principal Java Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: JAVA (.java file).\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID JAVA CODE.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, COMPILABLE, MEANINGFUL JAVA CODE. Preserve the developer's original class and structure.\n" +
                   "- Fix all syntax errors, typos (e.g., `Scann` -> `Scanner`, `Arralist` -> `ArrayList`, `prntln` -> `println`), and missing imports (e.g., `import java.util.Scanner;`).\n" +
                   "- RESOLVE LOGICAL BUGS: Ensure loops terminate properly (e.g., `while (a > 0)` with `a--`).\n" +
                   "- NEVER output lazy placeholder statements or empty blocks.\n" +
                   "- Output strictly valid JSON with:\n" +
                   "  - `fixCode`: the complete, 100% correct Java file source code.\n" +
                   "  - `explanation`: exactly 1 short sentence (under 15 words) summarizing what was fixed.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT (both syntax AND logic): output the EXACT original file code in `fixCode` unchanged, and set `explanation` to 'Code is already correct — no changes needed.'";
        } else {
            return "You are a Staff Principal Software Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: " + language + ".\n" +
                   "CRITICAL: Output must be in " + language + " only. Never output code in a different programming language.\n" +
                   "Fix all syntax and logical bugs.\n" +
                   "Output strictly valid JSON with `fixCode` and `explanation`.";
        }
    }

    public static final String WHOLE_FILE_FIX_SYSTEM_PROMPT = getWholeFileFixSystemPrompt("java");

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
        String langUpper = (language != null && !language.isBlank() ? language : "python").toUpperCase();
        prompt.append("Target Language: ").append(langUpper).append(" (file: ").append(filePath).append(")\n\n");
        prompt.append("CRITICAL: The output MUST be 100% ").append(langUpper).append(" code. DO NOT output code in any other language!\n\n");
        if (issuesSummary != null && !issuesSummary.isEmpty()) {
            prompt.append("Reported compiler/linter issues:\n").append(issuesSummary).append("\n\n");
        } else {
            prompt.append("Reported issues: None reported by basic linter, but check for CRITICAL LOGICAL ERRORS (such as infinite loops, undefined variables, wrong update direction, off-by-one errors).\n\n");
        }
        prompt.append("Current code:\n```\n").append(fileContent).append("\n```\n");
        prompt.append("\nINSTRUCTIONS:\n");
        prompt.append("- Fix all syntax errors, typos, and undefined variable names.\n");
        prompt.append("- CAREFULLY DETECT LOGIC BUGS: check every loop condition and variable update. If an infinite loop exists (such as `while a > 0:` with `a += 1` instead of `a -= 1`), FIX IT so the loop terminates properly!\n");
        prompt.append("- NEVER write `pass` or empty dummy blocks.\n");
        prompt.append("- The entire `fixCode` MUST be valid ").append(langUpper).append(" code.\n");
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
