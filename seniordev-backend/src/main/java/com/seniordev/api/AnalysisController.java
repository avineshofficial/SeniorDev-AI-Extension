package com.seniordev.api;

import com.seniordev.ai.FixResult;
import com.seniordev.ai.OllamaAnalysisEnricher;
import com.seniordev.ai.OllamaClient;
import com.seniordev.api.dto.*;
import com.seniordev.ai.FixVerifier;
import com.seniordev.core.*;
import com.seniordev.detectors.java.JavaCompileDetector;
import com.seniordev.detectors.java.JavaPmdDetector;
import com.seniordev.detectors.java.JavaSpotBugsDetector;
import com.seniordev.detectors.python.PythonCompileDetector;
import com.seniordev.detectors.python.PythonRuffDetector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final ProjectScanner projectScanner;
    private final JavaCompileDetector javaCompileDetector;
    private final JavaPmdDetector pmdDetector;
    private final JavaSpotBugsDetector spotBugsDetector;
    private final PythonCompileDetector pythonCompileDetector;
    private final PythonRuffDetector ruffDetector;
    private final AnalysisStore analysisStore;
    private final OllamaAnalysisEnricher ollamaEnricher;
    private final FixVerifier fixVerifier;
    private final OllamaClient ollamaClient;

    public AnalysisController(ProjectScanner projectScanner,
                               JavaCompileDetector javaCompileDetector,
                               JavaPmdDetector pmdDetector,
                               JavaSpotBugsDetector spotBugsDetector,
                               PythonCompileDetector pythonCompileDetector,
                               PythonRuffDetector ruffDetector,
                               AnalysisStore analysisStore,
                               OllamaAnalysisEnricher ollamaEnricher,
                               FixVerifier fixVerifier,
                               OllamaClient ollamaClient) {
        this.projectScanner = projectScanner;
        this.javaCompileDetector = javaCompileDetector;
        this.pmdDetector = pmdDetector;
        this.spotBugsDetector = spotBugsDetector;
        this.pythonCompileDetector = pythonCompileDetector;
        this.ruffDetector = ruffDetector;
        this.analysisStore = analysisStore;
        this.ollamaEnricher = ollamaEnricher;
        this.fixVerifier = fixVerifier;
        this.ollamaClient = ollamaClient;
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@RequestBody AnalyzeRequest req) {
        String projectRootStr = req.getProjectRoot();
        if (projectRootStr == null || projectRootStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path inputPath = Path.of(projectRootStr).toAbsolutePath().normalize();
        Path projectRoot;
        String targetFilePath = req.getTargetFilePath();
        if (Files.isRegularFile(inputPath)) {
            projectRoot = inputPath.getParent();
            if (targetFilePath == null || targetFilePath.isBlank()) {
                targetFilePath = inputPath.toString();
            }
        } else if (Files.isDirectory(inputPath)) {
            projectRoot = inputPath;
        } else {
            log.error("Project root does not exist: {}", inputPath);
            return ResponseEntity.badRequest().build();
        }

        log.info("=== Starting analysis of project: {} ===", projectRoot);

        Path targetPathObj = null;
        if (targetFilePath != null && !targetFilePath.isBlank()) {
            try {
                Path p = Path.of(targetFilePath).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) {
                    targetPathObj = p;
                }
            } catch (Exception ignored) {}
        }

        // Scan the project
        BuildContext ctx = projectScanner.scan(projectRoot, targetPathObj);

        List<Issue> allIssues = new ArrayList<>();

        String targetExt = "";
        if (targetPathObj != null) {
            String name = targetPathObj.getFileName().toString().toLowerCase();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) targetExt = name.substring(dot);
        }

        boolean isSingleJava = targetPathObj != null && targetExt.equals(".java");
        boolean isSinglePython = targetPathObj != null && targetExt.equals(".py");

        // Java detectors
        if (!isSinglePython) {
            // Run Java compile detector
            try {
                allIssues.addAll(javaCompileDetector.detect(projectRoot, ctx));
            } catch (Exception e) {
                log.warn("JavaCompileDetector failed: {}", e.getMessage());
            }
            
            // Only run PMD and SpotBugs for full project scans or when requested
            if (targetPathObj == null) {
                try {
                    allIssues.addAll(pmdDetector.detect(projectRoot, ctx));
                } catch (Exception e) {
                    log.warn("JavaPmdDetector failed: {}", e.getMessage());
                }
                
                try {
                    allIssues.addAll(spotBugsDetector.detect(projectRoot, ctx));
                } catch (Exception e) {
                    log.warn("JavaSpotBugsDetector failed: {}", e.getMessage());
                }
            }
        }

        // Python detectors
        if (!isSingleJava) {
            // Run Python compile detector
            try {
                allIssues.addAll(pythonCompileDetector.detect(projectRoot, ctx));
            } catch (Exception e) {
                log.warn("PythonCompileDetector failed: {}", e.getMessage());
            }
            
            // Run Ruff for Python
            try {
                allIssues.addAll(ruffDetector.detect(projectRoot, ctx));
            } catch (Exception e) {
                log.warn("PythonRuffDetector failed: {}", e.getMessage());
            }
        }

        // Deduplicate issues by filePath + line + message to prevent redundant noise
        java.util.Map<String, Issue> uniqueIssues = new java.util.LinkedHashMap<>();
        for (Issue issue : allIssues) {
            String key = (issue.getFilePath() != null ? issue.getFilePath() : "") + ":" +
                         issue.getLine() + ":" +
                         (issue.getMessage() != null ? issue.getMessage().trim() : "");
            uniqueIssues.putIfAbsent(key, issue);
        }
        allIssues = new ArrayList<>(uniqueIssues.values());

        // Populate and refine snippets for all issues across ALL languages
        populateSnippets(projectRoot, allIssues);

        // Filter by target file if specified
        if (targetFilePath != null && !targetFilePath.isBlank()) {
            final Path filterPathObj = targetPathObj != null ? targetPathObj : Path.of(targetFilePath).toAbsolutePath().normalize();
            final String targetFileName = filterPathObj.getFileName().toString();
            final String targetFullPath = filterPathObj.toString().replace("\\", "/");

            allIssues = allIssues.stream()
                .filter(issue -> {
                    if (issue.getFilePath() == null || issue.getFilePath().isBlank()) return false;
                    String p = issue.getFilePath().replace("\\", "/");
                    Path issuePathObj = Path.of(p);
                    String issueFileName = issuePathObj.getFileName().toString();
                    return issueFileName.equalsIgnoreCase(targetFileName) || 
                           p.endsWith(targetFullPath) || targetFullPath.endsWith(p);
                })
                .collect(Collectors.toList());
            log.info("Filtered issues for target file {}: {} issues remaining", targetFileName, allIssues.size());
        }

        // Store results
        String sessionId = UUID.randomUUID().toString();
        analysisStore.put(sessionId, allIssues);

        log.info("=== Analysis complete: {} issues found ===", allIssues.size());

        AnalyzeResponse response = new AnalyzeResponse(
            sessionId,
            projectRoot.toString(),
            allIssues,
            ctx.getBuildSystem().name(),
            !ctx.getResolvedClasspath().isEmpty()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/enrich")
    public ResponseEntity<List<Issue>> enrich(@RequestBody EnrichRequest req) {
        if (req.getSessionId() == null || !analysisStore.has(req.getSessionId())) {
            return ResponseEntity.notFound().build();
        }
        
        List<Issue> sessionIssues = analysisStore.get(req.getSessionId());
        List<Issue> toEnrich = new ArrayList<>();
        
        if (req.getIssueIds() != null && !req.getIssueIds().isEmpty()) {
            for (Issue issue : sessionIssues) {
                if (req.getIssueIds().contains(issue.getId())) {
                    toEnrich.add(issue);
                }
            }
        } else {
            toEnrich.addAll(sessionIssues);
        }
        
        if (!toEnrich.isEmpty()) {
            log.info("=== Enriching {} issues for session {} ===", toEnrich.size(), req.getSessionId());
            ollamaEnricher.enrich(toEnrich);
        }
        
        return ResponseEntity.ok(toEnrich);
    }

    @PostMapping("/fix-file")
    public ResponseEntity<WholeFileFixResponse> fixFile(@RequestBody WholeFileFixRequest req) {
        if (req.getFilePath() == null || req.getFileContent() == null) {
            return ResponseEntity.badRequest().build();
        }

        List<Issue> issues = req.getIssues();
        if (issues == null && req.getSessionId() != null && analysisStore.has(req.getSessionId())) {
            issues = analysisStore.get(req.getSessionId());
        }

        log.info("=== Generating whole file AI fix for: {} ===", req.getFilePath());

        if (!ollamaClient.isAvailable()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(new WholeFileFixResponse(
                    "Ollama is not running. Please start Ollama ('ollama serve') in your terminal.",
                    null,
                    0,
                    List.of()
                ));
        }

        FixResult fixResult = ollamaEnricher.fixWholeFile(
            req.getLanguage() != null ? req.getLanguage() : "auto",
            req.getFilePath(),
            req.getFileContent(),
            issues
        );

        if (fixResult == null || fixResult.fixCode() == null || fixResult.fixCode().isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new WholeFileFixResponse(
                    "AI failed to generate a fix for this file. Please check Ollama logs.",
                    null,
                    0,
                    List.of()
                ));
        }

        WholeFileFixResponse response = new WholeFileFixResponse(
            fixResult.explanation(),
            fixResult.fixCode(),
            issues != null ? issues.size() : 0,
            fixResult.errorsFound() != null
                ? fixResult.errorsFound().stream()
                    .map(e -> new WholeFileFixResponse.ErrorDetail(e.line(), e.message()))
                    .toList()
                : List.of()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<List<Issue>> verify(@RequestBody VerifyRequest req) {
        if (req.getSessionId() == null || !analysisStore.has(req.getSessionId())) {
            return ResponseEntity.notFound().build();
        }
        
        if (req.getProjectRoot() == null || req.getProjectRoot().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Path projectRoot = Path.of(req.getProjectRoot()).toAbsolutePath().normalize();

        List<Issue> sessionIssues = analysisStore.get(req.getSessionId());
        List<Issue> toVerify = new ArrayList<>();
        
        if (req.getIssueIds() != null && !req.getIssueIds().isEmpty()) {
            for (Issue issue : sessionIssues) {
                if (req.getIssueIds().contains(issue.getId())) {
                    toVerify.add(issue);
                }
            }
        } else {
            toVerify.addAll(sessionIssues);
        }
        
        if (!toVerify.isEmpty()) {
            log.info("=== Verifying {} issues for session {} ===", toVerify.size(), req.getSessionId());
            for (Issue issue : toVerify) {
                fixVerifier.verify(issue, projectRoot);
            }
        }
        
        return ResponseEntity.ok(toVerify);
    }

    /**
     * Free-form developer chat endpoint.
     * Accepts a question and optional context, returns an AI-generated answer.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Chat request: {}", req.getQuestion().length() > 100
            ? req.getQuestion().substring(0, 100) + "..." : req.getQuestion());

        // Build issue context if a session is active
        String issueContext = null;
        if (req.getSessionId() != null && analysisStore.has(req.getSessionId())) {
            List<Issue> issues = analysisStore.get(req.getSessionId());
            if (!issues.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (Issue issue : issues) {
                    if (count >= 5) {
                        sb.append("... and ").append(issues.size() - 5).append(" more issues\n");
                        break;
                    }
                    sb.append("- [").append(issue.getSeverity()).append("] ")
                      .append(issue.getFilePath()).append(":").append(issue.getLine())
                      .append(" — ").append(issue.getMessage()).append("\n");
                    count++;
                }
                issueContext = sb.toString();
            }
        }

        String answer = ollamaEnricher.chat(
            req.getQuestion(),
            req.getFilePath(),
            req.getFileContent(),
            issueContext
        );

        return ResponseEntity.ok(new ChatResponse(answer, ollamaClient.getModelName()));
    }

    /**
     * Enhanced health check that reports per-dependency status.
     * Checks: backend running, Java compiler, Ollama reachable + model present,
     * Maven on PATH, Ruff on PATH.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("backend", "running");

        // Java compiler
        boolean hasJavaCompiler = ToolProvider.getSystemJavaCompiler() != null;
        status.put("javaCompiler", hasJavaCompiler);

        // Ollama
        boolean ollamaAvailable = false;
        try {
            ollamaAvailable = ollamaClient.isAvailable();
        } catch (Exception e) {
            log.debug("Ollama health check failed: {}", e.getMessage());
        }
        status.put("ollamaAvailable", ollamaAvailable);
        status.put("ollamaModel", ollamaClient.getModelName());

        // Maven
        boolean mavenAvailable = isCommandAvailable("mvn", "--version");
        status.put("mavenAvailable", mavenAvailable);

        // Ruff
        boolean ruffAvailable = isCommandAvailable("ruff", "--version");
        status.put("ruffAvailable", ruffAvailable);

        // Overall status
        if (!hasJavaCompiler) {
            status.put("status", "DEGRADED");
            status.put("warning", "No Java compiler found — running on JRE instead of JDK");
        }
        if (!ollamaAvailable) {
            status.put("status", "DEGRADED");
            status.put("warning", "Ollama not running or model '" + ollamaClient.getModelName() + "' not found");
        }

        return ResponseEntity.ok(status);
    }

    @GetMapping("/issues")
    public ResponseEntity<List<Issue>> listIssues(@RequestParam String sessionId) {
        if (!analysisStore.has(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analysisStore.get(sessionId));
    }

    /**
     * Checks if a command-line tool is available on the system PATH.
     */
    private boolean isCommandAvailable(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes(); // consume output
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void populateSnippets(Path projectRoot, List<Issue> issues) {
        for (Issue issue : issues) {
            if (issue.getFilePath() == null || issue.getFilePath().isBlank()) {
                continue;
            }
            try {
                Path fullPath = projectRoot.resolve(Path.of(issue.getFilePath())).toAbsolutePath().normalize();
                if (!Files.exists(fullPath) || !Files.isRegularFile(fullPath)) {
                    continue;
                }
                String content = Files.readString(fullPath);
                String[] lines = content.split("\\r?\\n");
                int errorLine = issue.getLine() - 1; // 0-indexed
                
                if (errorLine >= 0 && errorLine < lines.length) {
                    if ("java".equalsIgnoreCase(issue.getLanguage())) {
                        issue.setSnippet(extractBalancedSnippet(lines, errorLine));
                    } else {
                        int start = Math.max(0, errorLine - 2);
                        int end = Math.min(lines.length, errorLine + 3);
                        StringBuilder snippet = new StringBuilder();
                        for (int i = start; i < end; i++) {
                            snippet.append(lines[i]).append("\n");
                        }
                        issue.setSnippet(snippet.toString().stripTrailing());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract snippet for {}: {}", issue.getFilePath(), e.getMessage());
            }
        }
    }

    private String extractBalancedSnippet(String[] lines, int errorLine) {
        int start = errorLine;
        while (start > 0 && start > errorLine - 10) {
            String line = lines[start].trim();
            if (line.endsWith("{") || line.contains("class ") || line.contains("interface ") || 
                line.contains("void ") || line.contains("public ") || line.contains("private ") ||
                line.contains("try ") || line.contains("catch ") || line.contains("if ") || line.contains("for ") || line.contains("while ")) {
                break;
            }
            start--;
        }
        if (start < 0) {
            start = Math.max(0, errorLine - 2);
        }

        int openBraces = 0;
        int closeBraces = 0;
        int end = start;
        boolean foundBraces = false;

        while (end < lines.length) {
            String line = lines[end];
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '{') {
                    openBraces++;
                    foundBraces = true;
                } else if (ch == '}') {
                    closeBraces++;
                }
            }
            end++;
            if (foundBraces && openBraces == closeBraces) {
                break;
            }
        }

        if (!foundBraces || openBraces != closeBraces) {
            start = Math.max(0, errorLine - 2);
            end = Math.min(lines.length, errorLine + 3);
        }

        StringBuilder snippet = new StringBuilder();
        for (int i = start; i < end; i++) {
            snippet.append(lines[i]).append("\n");
        }
        return snippet.toString().stripTrailing();
    }
}
