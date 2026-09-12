package com.seniordev.ai;

import com.seniordev.core.Issue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class SandboxWorkspace {

    public static Path copyProjectSubset(Issue issue, Path projectRoot) throws IOException {
        Path tempDir = Files.createTempDirectory("seniordev-sandbox-");
        
        Path relativePath = Paths.get(issue.getFilePath());
        Path absoluteOriginalFile = projectRoot.resolve(relativePath);
        Path targetPath = tempDir.resolve(relativePath);
        
        // Ensure parent directories exist in temp dir
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        
        Files.copy(absoluteOriginalFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        return tempDir;
    }

    public static void applyFix(Path sandbox, Issue issue, Path projectRoot) throws IOException {
        Path relativePath = Paths.get(issue.getFilePath());
        Path targetPath = sandbox.resolve(relativePath);

        String content = Files.readString(targetPath);
        if (issue.getSnippet() != null && issue.getAiFixCode() != null) {
            String updatedContent = content.replace(issue.getSnippet(), issue.getAiFixCode());
            Files.writeString(targetPath, updatedContent);
        }
    }
}
