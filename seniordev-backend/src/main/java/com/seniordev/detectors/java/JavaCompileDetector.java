package com.seniordev.detectors.java;

import com.seniordev.core.*;
import com.seniordev.build.BuildSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Detects Java compile errors using the JDK Compiler API with the resolved classpath.
 * Uses structured diagnostics instead of regex-parsing javac stderr.
 */
@Component
public class JavaCompileDetector implements IssueDetector {

    private static final Logger log = LoggerFactory.getLogger(JavaCompileDetector.class);

    @Override
    public List<String> supportedLanguages() {
        return List.of("java");
    }

    @Override
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.error("No Java compiler available. Ensure running on a JDK (not JRE).");
            return List.of();
        }

        // Only compile .java files from the discovered source files
        List<File> javaFiles;
        if (ctx.getTargetFilePath() != null && Files.isRegularFile(ctx.getTargetFilePath()) && ctx.getTargetFilePath().toString().endsWith(".java")) {
            javaFiles = List.of(ctx.getTargetFilePath().toFile());
        } else {
            javaFiles = ctx.getSourceFiles().stream()
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .map(Path::toFile)
                .collect(Collectors.toList());
        }

        if (javaFiles.isEmpty()) {
            log.info("No Java source files found to compile");
            return List.of();
        }

        log.info("Compiling {} Java files with classpath: {} chars",
                 javaFiles.size(),
                 ctx.getResolvedClasspath().length());

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(javaFiles);

            // Build compiler options
            List<String> options = new ArrayList<>();

            // Add classpath if resolved
            String classpath = ctx.getResolvedClasspath();
            if (classpath != null && !classpath.isEmpty()) {
                options.add("-classpath");
                options.add(classpath);
            }

            options.add("-Xlint:all");
            options.add("-proc:none");  // Skip annotation processing

            // Create temp output directory
            Path outputDir = Files.createTempDirectory("seniordev-compile-out");
            outputDir.toFile().deleteOnExit();
            ctx.setCompiledClassesDir(outputDir);
            options.add("-d");
            options.add(outputDir.toString());

            log.debug("Compiler options: {}", options);

            JavaCompiler.CompilationTask task =
                compiler.getTask(null, fm, diagnostics, options, null, units);
            task.call();

        } catch (IOException e) {
            throw new UncheckedIOException("Failed during compilation", e);
        }

        List<Issue> issues = diagnostics.getDiagnostics().stream()
            .filter(d -> d.getSource() != null)
            .map(d -> toIssue(d, projectRoot))
            .collect(Collectors.toList());

        log.info("Java compile detector found {} issues", issues.size());
        return issues;
    }

    private Issue toIssue(Diagnostic<? extends JavaFileObject> d, Path projectRoot) {
        Issue issue = new Issue();
        issue.setLanguage("java");
        issue.setType(IssueType.COMPILE_ERROR);
        issue.setSeverity(
            d.getKind() == Diagnostic.Kind.ERROR ? Severity.CRITICAL : Severity.MEDIUM
        );

        // Extract file path, making it relative to project root for cleaner display
        String filePath = d.getSource().toUri().getPath();
        // On Windows, URI path starts with /C:/ â€” normalize it
        if (filePath != null && filePath.matches("^/[A-Za-z]:.*")) {
            filePath = filePath.substring(1);
        }
        try {
            Path absPath = Path.of(filePath);
            if (absPath.startsWith(projectRoot)) {
                filePath = projectRoot.relativize(absPath).toString();
            }
        } catch (Exception e) {
            // Keep absolute path if relativization fails
        }

        issue.setFilePath(filePath);
        issue.setLine((int) d.getLineNumber());
        issue.setMessage(d.getMessage(Locale.ROOT));

        // Try to extract a code snippet around the error line
        try {
            if (d.getSource() != null) {
                CharSequence content = d.getSource().getCharContent(true);
                String[] lines = content.toString().split("\n");
                int errorLine = (int) d.getLineNumber() - 1; // 0-indexed
                if (errorLine >= 0 && errorLine < lines.length) {
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
            // Snippet extraction is best-effort
        }

        return issue;
    }
}

