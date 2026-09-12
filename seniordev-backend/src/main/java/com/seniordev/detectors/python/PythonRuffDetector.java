package com.seniordev.detectors.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniordev.core.BuildContext;
import com.seniordev.core.Issue;
import com.seniordev.core.IssueDetector;
import com.seniordev.core.IssueType;
import com.seniordev.core.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class PythonRuffDetector implements IssueDetector {

    private static final Logger log = LoggerFactory.getLogger(PythonRuffDetector.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<String> supportedLanguages() {
        return List.of("python");
    }

    @Override
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        List<Issue> issues = new ArrayList<>();
        
        Path targetPath = (ctx != null && ctx.getTargetFilePath() != null && Files.isRegularFile(ctx.getTargetFilePath()))
            ? ctx.getTargetFilePath()
            : null;

        if (targetPath != null && !targetPath.toString().endsWith(".py")) {
            return issues;
        }

        if (targetPath == null) {
            try (Stream<Path> walk = Files.walk(projectRoot, 6)) {
                boolean hasPython = walk.anyMatch(p -> p.toString().endsWith(".py"));
                if (!hasPython) {
                    return issues;
                }
            } catch (Exception e) {
                return issues;
            }
        }

        try {
            List<String> command = new ArrayList<>();
            command.add("ruff");
            command.add("check");
            command.add("--select=E,F,W,C,N,B,A,C4,PT,RET,SIM,ARG,PTH,PL,RUF");
            command.add("--output-format=json");
            command.add(targetPath != null ? targetPath.toAbsolutePath().toString() : projectRoot.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile());

            Process proc = pb.start();
            InputStream stdoutStream = proc.getInputStream();
            InputStream stderrStream = proc.getErrorStream();

            String output = new String(stdoutStream.readAllBytes());
            String errOutput = new String(stderrStream.readAllBytes());
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                log.warn("Ruff analysis timed out after 30s");
                return issues;
            }

            if (output != null && !output.trim().isEmpty() && output.trim().startsWith("[")) {
                JsonNode root = mapper.readTree(output);
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        Issue issue = new Issue();
                        issue.setLanguage("python");
                        
                        String code = node.has("code") && !node.get("code").isNull() ? node.get("code").asText() : "";
                        String name = node.has("name") ? node.get("name").asText() : code;
                        if (code.startsWith("E999") || name.toLowerCase().contains("syntax")) {
                            issue.setType(IssueType.COMPILE_ERROR);
                        } else if (code.startsWith("F821") || code.startsWith("F82") || code.startsWith("B")) {
                            issue.setType(IssueType.LOGIC_ISSUE);
                        } else if (code.startsWith("S")) {
                            issue.setType(IssueType.SECURITY_RISK);
                        } else {
                            issue.setType(IssueType.LINT_WARNING);
                        }

                        String severityStr = node.has("severity") ? node.get("severity").asText() : "";
                        issue.setSeverity(mapSeverity(code, severityStr));
                        
                        String msg = node.has("message") ? node.get("message").asText() : "Ruff violation";
                        issue.setMessage("Ruff [" + (code.isEmpty() ? name : code) + "]: " + msg);
                        
                        if (node.has("location")) {
                            JsonNode loc = node.get("location");
                            issue.setLine(loc.has("row") ? loc.get("row").asInt() : 1);
                        }
                        
                        if (node.has("filename")) {
                            String rawFile = node.get("filename").asText();
                            try {
                                Path absFile = Path.of(rawFile).toAbsolutePath();
                                Path relPath = projectRoot.relativize(absFile);
                                issue.setFilePath(relPath.toString());
                            } catch (Exception e) {
                                issue.setFilePath(Path.of(rawFile).getFileName().toString());
                            }
                        }
                        
                        issues.add(issue);
                    }
                }
            } else if (errOutput != null && errOutput.contains("SyntaxError")) {
                log.warn("Ruff reported stderr syntax error: {}", errOutput);
            }

        } catch (Exception e) {
            log.error("Ruff analysis failed: {}", e.getMessage(), e);
        }

        return issues;
    }

    private Severity mapSeverity(String code, String severityStr) {
        if (code != null && (code.startsWith("F821") || code.startsWith("E999"))) return Severity.HIGH;
        if (severityStr == null) return Severity.MEDIUM;
        return switch (severityStr.toLowerCase()) {
            case "error", "fatal" -> Severity.HIGH;
            case "warning" -> Severity.MEDIUM;
            default -> Severity.INFO;
        };
    }
}
