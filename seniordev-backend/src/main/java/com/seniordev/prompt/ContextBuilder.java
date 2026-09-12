package com.seniordev.prompt;

import com.seniordev.core.Issue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds cross-file context for AI prompts by finding symbol declarations
 * referenced in issue messages. When an issue says "cannot find symbol: variable X",
 * this class greps the project for X's declaration and includes a short excerpt,
 * keeping context tokens minimal for the VRAM budget.
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    /**
     * Patterns that indicate a symbol reference in issue messages.
     * Captures the symbol name from common compiler/linter error formats.
     */
    private static final List<Pattern> SYMBOL_PATTERNS = List.of(
        Pattern.compile("cannot find symbol[:\\s]+(?:variable|method|class)\\s+(\\w+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("cannot resolve[:\\s]+(?:symbol|method|class)\\s+'?(\\w+)'?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("undefined[:\\s]+(?:variable|name|reference)\\s+'?(\\w+)'?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("is not defined\\s*[.:]\\s*'?(\\w+)'?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Undefined name `(\\w+)`", Pattern.CASE_INSENSITIVE)
    );

    /** Maximum number of context excerpts to include */
    private static final int MAX_CONTEXT_EXCERPTS = 3;

    /** Lines of context around a found symbol */
    private static final int CONTEXT_LINES = 3;

    /**
     * Extracts cross-file context relevant to the given issue.
     * Searches for symbol declarations referenced in the issue message
     * across the project source files.
     *
     * @param issue       the issue to build context for
     * @param projectRoot the project root directory
     * @return formatted context string, or empty string if no relevant context found
     */
    public String buildContext(Issue issue, Path projectRoot) {
        if (issue.getMessage() == null || issue.getMessage().isEmpty()) {
            return "";
        }

        // Extract symbol names from the issue message
        List<String> symbols = extractSymbols(issue.getMessage());
        if (symbols.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        int excerptCount = 0;

        for (String symbol : symbols) {
            if (excerptCount >= MAX_CONTEXT_EXCERPTS) {
                break;
            }

            List<String> excerpts = findSymbolDeclarations(symbol, projectRoot, issue.getFilePath());
            for (String excerpt : excerpts) {
                if (excerptCount >= MAX_CONTEXT_EXCERPTS) {
                    break;
                }
                context.append(excerpt).append("\n");
                excerptCount++;
            }
        }

        return context.toString().trim();
    }

    /**
     * Extracts symbol names from an issue message using known patterns.
     */
    private List<String> extractSymbols(String message) {
        List<String> symbols = new ArrayList<>();
        for (Pattern pattern : SYMBOL_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                String symbol = matcher.group(1);
                if (symbol != null && !symbol.isEmpty() && symbol.length() > 1) {
                    symbols.add(symbol);
                }
            }
        }
        return symbols;
    }

    /**
     * Searches project source files for declarations of the given symbol.
     * Skips the file that contains the issue itself.
     *
     * @param symbol      the symbol name to search for
     * @param projectRoot the project root
     * @param issueFile   the file containing the issue (to exclude from search)
     * @return list of formatted context excerpts
     */
    private List<String> findSymbolDeclarations(String symbol, Path projectRoot, String issueFile) {
        List<String> excerpts = new ArrayList<>();

        // Declaration patterns to search for
        List<Pattern> declPatterns = List.of(
            Pattern.compile("(?:public|private|protected|static|final|var|val|let|const|def)\\s+.*\\b" + Pattern.quote(symbol) + "\\b"),
            Pattern.compile("(?:class|interface|enum|record)\\s+" + Pattern.quote(symbol) + "\\b"),
            Pattern.compile("\\b" + Pattern.quote(symbol) + "\\s*="),
            Pattern.compile("def\\s+" + Pattern.quote(symbol) + "\\s*\\(")
        );

        try (Stream<Path> walk = Files.walk(projectRoot)) {
            List<Path> sourceFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".java") || name.endsWith(".py") ||
                           name.endsWith(".ts") || name.endsWith(".js");
                })
                .filter(p -> {
                    // Skip the issue's own file
                    if (issueFile == null) return true;
                    String pathStr = p.toString().replace("\\", "/");
                    String issueStr = issueFile.replace("\\", "/");
                    return !pathStr.endsWith(issueStr) && !issueStr.endsWith(pathStr);
                })
                .limit(200) // Don't scan too many files
                .toList();

            for (Path file : sourceFiles) {
                if (excerpts.size() >= MAX_CONTEXT_EXCERPTS) {
                    break;
                }

                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (Pattern declPattern : declPatterns) {
                            if (declPattern.matcher(line).find()) {
                                String excerpt = formatExcerpt(file, lines, i, projectRoot);
                                excerpts.add(excerpt);
                                break; // Found in this file, move on
                            }
                        }
                        if (excerpts.size() >= MAX_CONTEXT_EXCERPTS) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    log.debug("Could not read file for context: {}", file);
                }
            }
        } catch (IOException e) {
            log.debug("Could not walk project for context: {}", e.getMessage());
        }

        return excerpts;
    }

    /**
     * Formats a context excerpt with file path and surrounding lines.
     */
    private String formatExcerpt(Path file, List<String> lines, int lineIndex, Path projectRoot) {
        String relPath;
        try {
            relPath = projectRoot.relativize(file).toString();
        } catch (Exception e) {
            relPath = file.getFileName().toString();
        }

        int start = Math.max(0, lineIndex - 1);
        int end = Math.min(lines.size(), lineIndex + CONTEXT_LINES);

        StringBuilder sb = new StringBuilder();
        sb.append("// From ").append(relPath).append(" (line ").append(lineIndex + 1).append("):\n");
        for (int i = start; i < end; i++) {
            sb.append(lines.get(i)).append("\n");
        }

        return sb.toString().stripTrailing();
    }
}
