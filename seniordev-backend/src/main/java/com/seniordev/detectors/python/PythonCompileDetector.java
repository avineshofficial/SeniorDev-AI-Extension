package com.seniordev.detectors.python;

import com.seniordev.core.BuildContext;
import com.seniordev.core.Issue;
import com.seniordev.core.IssueDetector;
import com.seniordev.core.IssueType;
import com.seniordev.core.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class PythonCompileDetector implements IssueDetector {

    private static final Logger log = LoggerFactory.getLogger(PythonCompileDetector.class);
    private static final Pattern LINE_PATTERN = Pattern.compile("line\\s+(\\d+)");

    @Override
    public List<String> supportedLanguages() {
        return List.of("python");
    }

    @Override
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        List<Issue> issues = new ArrayList<>();

        List<Path> pyFiles = new ArrayList<>();
        if (ctx != null && ctx.getTargetFilePath() != null && Files.isRegularFile(ctx.getTargetFilePath())) {
            if (ctx.getTargetFilePath().toString().endsWith(".py")) {
                pyFiles.add(ctx.getTargetFilePath());
            } else {
                return issues;
            }
        } else {
            try (Stream<Path> walk = Files.walk(projectRoot, 6)) {
                pyFiles = walk.filter(p -> p.toString().endsWith(".py"))
                              .filter(p -> !p.toString().contains("/node_modules/"))
                              .filter(p -> !p.toString().contains("/.venv/"))
                              .filter(p -> !p.toString().contains("/venv/"))
                              .filter(p -> !p.toString().contains("/__pycache__/"))
                              .filter(p -> !p.toString().contains("/.git/"))
                              .toList();
            } catch (Exception e) {
                return issues;
            }
        }

        if (pyFiles.isEmpty()) {
            return issues;
        }

        String pythonCmd = findPythonCmd();
        if (pythonCmd == null) {
            log.warn("Neither python3 nor python is available on PATH.");
            return issues;
        }

        for (Path pyFile : pyFiles) {
            try {
                ProcessBuilder pb = new ProcessBuilder(pythonCmd, "-m", "py_compile", pyFile.toAbsolutePath().toString())
                    .directory(projectRoot.toFile());
                pb.redirectErrorStream(true);

                Process proc = pb.start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    continue;
                }

                if (proc.exitValue() != 0) {
                    String outStr = output.toString().trim();
                    Issue issue = new Issue();
                    issue.setLanguage("python");
                    issue.setType(IssueType.COMPILE_ERROR);
                    issue.setSeverity(Severity.HIGH);

                    try {
                        Path relPath = projectRoot.relativize(pyFile.toAbsolutePath());
                        issue.setFilePath(relPath.toString());
                    } catch (Exception e) {
                        issue.setFilePath(pyFile.getFileName().toString());
                    }

                    int errorLine = 1;
                    Matcher m = LINE_PATTERN.matcher(outStr);
                    if (m.find()) {
                        try {
                            errorLine = Integer.parseInt(m.group(1));
                        } catch (NumberFormatException ignored) {}
                    }
                    issue.setLine(errorLine);

                    String msg = "Python Syntax Error: ";
                    if (outStr.contains("SyntaxError:") || outStr.contains("IndentationError:") || outStr.contains("NameError:")) {
                        int idx = Math.max(outStr.indexOf("SyntaxError:"), Math.max(outStr.indexOf("IndentationError:"), outStr.indexOf("NameError:")));
                        msg += outStr.substring(idx).trim();
                    } else {
                        msg += outStr;
                    }
                    if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
                    issue.setMessage(msg);

                    issues.add(issue);
                } else {
                    // Check for logical bugs (such as infinite loops)
                    checkLogicalErrors(pyFile, projectRoot, issues);
                }
            } catch (Exception e) {
                log.warn("Python compile check failed for {}: {}", pyFile, e.getMessage());
            }
        }

        return issues;
    }

    private void checkLogicalErrors(Path pyFile, Path projectRoot, List<Issue> issues) {
        try {
            List<String> lines = Files.readAllLines(pyFile);
            Pattern whileGreater = Pattern.compile("^(\\s*)while\\s+([a-zA-Z_]\\w*)\\s*>\\s*([^:]+):");
            Pattern whileLess = Pattern.compile("^(\\s*)while\\s+([a-zA-Z_]\\w*)\\s*<\\s*([^:]+):");

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher mG = whileGreater.matcher(line);
                Matcher mL = whileLess.matcher(line);

                if (mG.find()) {
                    String indent = mG.group(1);
                    String var = mG.group(2);
                    boolean increments = false;
                    for (int j = i + 1; j < lines.size(); j++) {
                        String sub = lines.get(j);
                        if (sub.isBlank()) continue;
                        int subIndent = sub.indexOf(sub.trim());
                        if (subIndent <= indent.length()) break;
                        if (sub.matches(".*\\b" + var + "\\s*\\+=\\s*.*") ||
                            sub.matches(".*\\b" + var + "\\s*=\\s*" + var + "\\s*\\+.*")) {
                            increments = true;
                            break;
                        }
                    }
                    if (increments) {
                        Issue issue = new Issue();
                        issue.setLanguage("python");
                        issue.setType(IssueType.LOGIC_ISSUE);
                        issue.setSeverity(Severity.HIGH);
                        issue.setLine(i + 1);
                        try {
                            issue.setFilePath(projectRoot.relativize(pyFile.toAbsolutePath()).toString());
                        } catch (Exception e) {
                            issue.setFilePath(pyFile.getFileName().toString());
                        }
                        issue.setMessage("Logical Error: Infinite loop detected. Variable '" + var + "' is incremented inside 'while " + var + " > ...', so the loop will never terminate.");
                        issues.add(issue);
                    }
                } else if (mL.find()) {
                    String indent = mL.group(1);
                    String var = mL.group(2);
                    boolean decrements = false;
                    for (int j = i + 1; j < lines.size(); j++) {
                        String sub = lines.get(j);
                        if (sub.isBlank()) continue;
                        int subIndent = sub.indexOf(sub.trim());
                        if (subIndent <= indent.length()) break;
                        if (sub.matches(".*\\b" + var + "\\s*-=\\s*.*") ||
                            sub.matches(".*\\b" + var + "\\s*=\\s*" + var + "\\s*-.*")) {
                            decrements = true;
                            break;
                        }
                    }
                    if (decrements) {
                        Issue issue = new Issue();
                        issue.setLanguage("python");
                        issue.setType(IssueType.LOGIC_ISSUE);
                        issue.setSeverity(Severity.HIGH);
                        issue.setLine(i + 1);
                        try {
                            issue.setFilePath(projectRoot.relativize(pyFile.toAbsolutePath()).toString());
                        } catch (Exception e) {
                            issue.setFilePath(pyFile.getFileName().toString());
                        }
                        issue.setMessage("Logical Error: Infinite loop detected. Variable '" + var + "' is decremented inside 'while " + var + " < ...', so the loop will never terminate.");
                        issues.add(issue);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Logical error check failed for {}: {}", pyFile, e.getMessage());
        }
    }

    private String findPythonCmd() {
        if (isCommandAvailable("python3")) return "python3";
        if (isCommandAvailable("python")) return "python";
        return null;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process proc = new ProcessBuilder(cmd, "--version").start();
            return proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
