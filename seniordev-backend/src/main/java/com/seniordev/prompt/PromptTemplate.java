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

    public static String getUniversalLogicRules() {
        return "- PRESERVE USER INTENT & PREVENT LOGIC INVERSIONS / DEAD CODE / INFINITE LOOPS:\n" +
               "  1) WHILE LOOPS:\n" +
               "     - Compare `start` value vs `limit`:\n" +
               "       * If `start > limit` (e.g. `a = 10`, limit `0`): The loop is counting DOWN. Condition MUST be `> limit` (e.g. `while a > 0:` or `while (a > 0)`). Step MUST decrement (`a -= 1` / `a--`). If condition was written `< 0`, it is initial dead code; fix it to `> 0`.\n" +
               "       * If `start < limit` (e.g. `a = 1`, limit `10`): The loop is counting UP. Condition MUST be `< limit` (e.g. `while a < 10:` or `while (a < 10)`). Step MUST increment (`a += 1` / `a++`).\n" +
               "  2) FOR LOOPS:\n" +
               "     - Python `range(start, stop)`: `range(10, 0)` produces an EMPTY sequence! For counting down, step MUST be `-1` -> `range(10, 0, -1)`.\n" +
               "     - C-Style `for (int i = start; condition; update)`: For counting down (`start > limit`), condition MUST be `>` / `>=` and update MUST decrement (`i--`). For counting up (`start < limit`), condition MUST be `<` / `<=` and update MUST increment (`i++`).\n" +
               "  3) YODA CONDITIONS (in while, for, if/elif):\n" +
               "     - When fixing Yoda conditions (e.g., `while 1 < a:`, `if (0 == x)`, `if (NULL == ptr)`), ONLY flip operands and operators (e.g., `while a > 1:`, `if (x == 0)`).\n" +
               "     - RULE: NEVER change comparison values during Yoda reordering! `while 1 < a:` MUST become `while a > 1:`, NOT `while a > 0:`.\n";
    }

    public static String getWholeFileFixSystemPrompt(String language) {
        String lang = (language != null ? language : "").toLowerCase();
        if (lang.contains("py")) {
            return "You are a Staff Principal Python Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: PYTHON (.py file).\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID PYTHON CODE.\n" +
                   "NEVER output Java, C++, or any other programming language. NEVER use Java keywords like `public class`, `import java.util`, or semicolons `;`.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, COMPILABLE, MEANINGFUL PYTHON CODE.\n" +
                   getUniversalLogicRules() +
                   "- Fix undefined names (e.g., if `j` is used but `a` was initialized, correct `print(j)` to `print(a)`).\n" +
                   "- NEVER output lazy placeholder statements like `pass`, `// TODO`, or empty blocks.\n" +
                   "- Output strictly valid JSON with:\n" +
                   "  - `fixCode`: the complete, 100% correct Python file source code.\n" +
                   "  - `explanation`: exactly 1 short sentence summarizing what was fixed.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT (both syntax AND logic): output the EXACT original file code in `fixCode` unchanged, and set `explanation` to 'Code is already correct — no changes needed.'";
        } else if (lang.contains("java")) {
            return "You are a Staff Principal Java Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: JAVA (.java file).\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID JAVA CODE.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, COMPILABLE, MEANINGFUL JAVA CODE. Preserve the developer's original class and structure.\n" +
                   getUniversalLogicRules() +
                   "- Fix all syntax errors, typos (e.g., `Scann` -> `Scanner`, `Arralist` -> `ArrayList`, `prntln` -> `println`), and missing imports (e.g., `import java.util.Scanner;`).\n" +
                   "- NEVER output lazy placeholder statements or empty blocks.\n" +
                   "- Output strictly valid JSON with:\n" +
                   "  - `fixCode`: the complete, 100% correct Java file source code.\n" +
                   "  - `explanation`: exactly 1 short sentence summarizing what was fixed.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT (both syntax AND logic): output the EXACT original file code in `fixCode` unchanged, and set `explanation` to 'Code is already correct — no changes needed.'";
        } else if (lang.contains("js") || lang.contains("ts") || lang.contains("javascript") || lang.contains("typescript")) {
            return "You are a Staff Principal JavaScript/TypeScript Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: JAVASCRIPT/TYPESCRIPT.\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID JAVASCRIPT/TYPESCRIPT CODE.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, EXECUTABLE, MEANINGFUL JS/TS CODE.\n" +
                   getUniversalLogicRules() +
                   "- Fix all syntax errors, missing imports, and undefined variables.\n" +
                   "- Output strictly valid JSON with `fixCode` and `explanation`.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT: output the EXACT original file code in `fixCode` unchanged.";
        } else if (lang.contains("c") || lang.contains("cpp")) {
            return "You are a Staff Principal C/C++ Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: C/C++.\n" +
                   "CRITICAL REQUIREMENT: Output MUST BE 100% PURE, VALID C/C++ CODE.\n\n" +
                   "CRITICAL RULES:\n" +
                   "- Produce 100% PERFECT, COMPILABLE, MEANINGFUL C/C++ CODE.\n" +
                   getUniversalLogicRules() +
                   "- Fix syntax errors, pointer dereferences, and Yoda conditionals (e.g. `if (NULL == ptr)` -> `if (ptr == NULL)`).\n" +
                   "- Output strictly valid JSON with `fixCode` and `explanation`.\n" +
                   "- IF THE CODE IS ALREADY 100% CORRECT: output the EXACT original file code in `fixCode` unchanged.";
        } else {
            return "You are a Staff Principal Software Engineer, Senior Code Reviewer, and Logic Fixer.\n" +
                   "Target Language: " + language + ".\n" +
                   "CRITICAL: Output must be in " + language + " only. Never output code in a different programming language.\n" +
                   getUniversalLogicRules() +
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
            prompt.append("Reported issues: Check for CRITICAL LOGICAL ERRORS (such as dead code conditions like `while a < 0` when `a = 10`, empty `range(10, 0)` in for-loops, infinite loops, undefined variables, wrong update direction).\n\n");
        }
        prompt.append("Current code:\n```\n").append(fileContent).append("\n```\n");
        prompt.append("\nINSTRUCTIONS:\n");
        prompt.append("- Analyze ALL control flow constructs (while loops, for loops, if/elif conditions):\n");
        prompt.append("  * while loop: condition must be True at start (e.g., if `a = 10` and `a -= 1`, condition must be `while a > 0:`, NOT `while a < 0:`).\n");
        prompt.append("  * for loop: in Python `range(10, 0)`, add step `-1` -> `range(10, 0, -1)`. In C-style `for (int i=10; i>0; i--)`, preserve counting down.\n");
        prompt.append("  * Yoda conditions: for `while 1 < a:` or `if 0 == x:`, ONLY flip operands to `a > 1` / `x == 0`. NEVER change values!\n");
        prompt.append("- Ensure loops progress towards termination without infinite loops.\n");
        prompt.append("- Fix all syntax errors, typos, and undefined variable names.\n");
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
