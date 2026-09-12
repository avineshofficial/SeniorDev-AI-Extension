package com.seniordev.detectors.java;

import com.seniordev.core.BuildContext;
import com.seniordev.core.Issue;
import com.seniordev.core.IssueDetector;
import com.seniordev.core.IssueType;
import com.seniordev.core.Severity;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import net.sourceforge.pmd.lang.rule.RulePriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class JavaPmdDetector implements IssueDetector {

    private static final Logger log = LoggerFactory.getLogger(JavaPmdDetector.class);

    @Override
    public List<String> supportedLanguages() {
        return List.of("java");
    }

    @Override
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        List<Issue> issues = new ArrayList<>();
        Path srcDir = projectRoot.resolve("src");
        if (!srcDir.toFile().exists()) {
            return issues; // No src directory, nothing to analyze
        }

        PMDConfiguration config = new PMDConfiguration();
        config.setMinimumPriority(RulePriority.MEDIUM);
        config.addRuleSet("category/java/bestpractices.xml");
        config.addRuleSet("category/java/errorprone.xml");
        config.addRuleSet("category/java/performance.xml");
        
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            // Add the source directory to PMD's file list
            pmd.files().addDirectory(srcDir);
            
            Report report = pmd.performAnalysisAndCollectReport();
            
            for (RuleViolation violation : report.getViolations()) {
                Issue issue = new Issue(
                    "java",
                    IssueType.LINT_WARNING,
                    mapSeverity(violation.getRule().getPriority()),
                    violation.getFileId().getOriginalPath(),
                    violation.getBeginLine(),
                    "PMD (" + violation.getRule().getName() + "): " + violation.getDescription()
                );
                issues.add(issue);
            }
        } catch (Exception e) {
            log.error("PMD analysis failed: {}", e.getMessage(), e);
        }

        return issues;
    }

    private Severity mapSeverity(RulePriority priority) {
        return switch (priority) {
            case HIGH -> Severity.HIGH;
            case MEDIUM_HIGH -> Severity.MEDIUM;
            case MEDIUM -> Severity.MEDIUM;
            case MEDIUM_LOW -> Severity.INFO;
            case LOW -> Severity.INFO;
        };
    }
}
