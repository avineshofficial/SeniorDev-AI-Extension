package com.seniordev.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.springframework.stereotype.Service;

import com.seniordev.build.ClasspathResolver;
import com.seniordev.core.Issue;
import com.seniordev.core.IssueType;
import com.seniordev.core.VerificationStatus;
import com.seniordev.detectors.java.JavaPmdDetector;
import com.seniordev.detectors.python.PythonRuffDetector;

@Service
public class FixVerifier {

    private final JavaPmdDetector pmdDetector;
    private final PythonRuffDetector ruffDetector;
    private final ClasspathResolver classpathResolver;

    public FixVerifier(JavaPmdDetector pmdDetector, PythonRuffDetector ruffDetector, ClasspathResolver classpathResolver) {
        this.pmdDetector = pmdDetector;
        this.ruffDetector = ruffDetector;
        this.classpathResolver = classpathResolver;
    }

    public void verify(Issue issue, Path projectRoot) {
        if (issue.getAiFixCode() == null || issue.getAiFixCode().isEmpty()) {
            issue.setVerificationStatus(VerificationStatus.VERIFICATION_SKIPPED);
            issue.setVerificationDetail("No AI fix code available");
            return;
        }

        try {
            Path sandbox = SandboxWorkspace.copyProjectSubset(issue, projectRoot);
            SandboxWorkspace.applyFix(sandbox, issue, projectRoot);

            if (issue.getType() == IssueType.COMPILE_ERROR) {
                if (issue.getLanguage().equalsIgnoreCase("java")) {
                    recompileSingleFile(sandbox, issue, projectRoot);
                } else {
                    issue.setVerificationStatus(VerificationStatus.VERIFICATION_SKIPPED);
                    issue.setVerificationDetail("Single file recompilation not supported for language: " + issue.getLanguage());
                }
            } else if (issue.getType() == IssueType.LINT_WARNING) {
                rerunDetector(sandbox, issue, projectRoot);
            } else {
                issue.setVerificationStatus(VerificationStatus.VERIFICATION_SKIPPED);
                issue.setVerificationDetail("Verification not implemented for issue type: " + issue.getType());
            }

        } catch (Exception e) {
            issue.setVerificationStatus(VerificationStatus.VERIFICATION_FAILED);
            issue.setVerificationDetail("Sandbox error: " + e.getMessage());
        }
    }

    private void recompileSingleFile(Path sandbox, Issue issue, Path projectRoot) throws IOException, InterruptedException {
        String classpath = classpathResolver.resolve(projectRoot);
        Path originalFile = Paths.get(issue.getFilePath());
        Path targetPath = sandbox.resolve(originalFile);

        ProcessBuilder pb = new ProcessBuilder("javac", "-cp", classpath, targetPath.toAbsolutePath().toString())
            .directory(sandbox.toFile())
            .redirectErrorStream(true);

        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        if (exitCode == 0) {
            issue.setVerificationStatus(VerificationStatus.VERIFIED_FIXED);
            issue.setVerificationDetail("Compiled successfully");
        } else {
            issue.setVerificationStatus(VerificationStatus.VERIFICATION_FAILED);
            issue.setVerificationDetail(output);
        }
    }

    private void rerunDetector(Path sandbox, Issue issue, Path projectRoot) {
        List<Issue> newIssues;
        if (issue.getLanguage().equalsIgnoreCase("java")) {
            if (issue.getMessage().startsWith("PMD")) {
                newIssues = pmdDetector.detect(sandbox, null); // BuildContext is null in PMD
            } else {
                // SpotBugs skipped for now
                issue.setVerificationStatus(VerificationStatus.VERIFICATION_SKIPPED);
                issue.setVerificationDetail("Verification skipped for non-PMD Java lint issues");
                return;
            }
        } else if (issue.getLanguage().equalsIgnoreCase("python")) {
            newIssues = ruffDetector.detect(sandbox, null);
        } else {
            issue.setVerificationStatus(VerificationStatus.VERIFICATION_SKIPPED);
            issue.setVerificationDetail("Verification skipped for language: " + issue.getLanguage());
            return;
        }

        // Check if the original issue is gone
        boolean isFixed = true;
        for (Issue newIssue : newIssues) {
            // Rough match: if the new issue shares the same rule name or similar message on a similar line
            if (newIssue.getMessage().equals(issue.getMessage()) || 
                (newIssue.getMessage().split(":")[0].equals(issue.getMessage().split(":")[0]))) {
                isFixed = false;
                issue.setVerificationDetail("Issue still present: " + newIssue.getMessage());
                break;
            }
        }

        if (isFixed) {
            if (!newIssues.isEmpty()) {
                issue.setVerificationStatus(VerificationStatus.VERIFICATION_FAILED);
                issue.setVerificationDetail("Fix introduced new issues: " + newIssues.get(0).getMessage());
            } else {
                issue.setVerificationStatus(VerificationStatus.VERIFIED_FIXED);
                issue.setVerificationDetail("Fix passed detector.");
            }
        } else {
            issue.setVerificationStatus(VerificationStatus.VERIFICATION_FAILED);
        }
    }
}
