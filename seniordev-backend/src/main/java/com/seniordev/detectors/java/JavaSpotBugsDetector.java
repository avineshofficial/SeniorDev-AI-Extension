package com.seniordev.detectors.java;

import com.seniordev.build.ClasspathResolver;
import com.seniordev.core.BuildContext;
import com.seniordev.core.Issue;
import com.seniordev.core.IssueDetector;
import com.seniordev.core.IssueType;
import com.seniordev.core.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class JavaSpotBugsDetector implements IssueDetector {

    private static final Logger log = LoggerFactory.getLogger(JavaSpotBugsDetector.class);

    private final ClasspathResolver classpathResolver;

    public JavaSpotBugsDetector(ClasspathResolver classpathResolver) {
        this.classpathResolver = classpathResolver;
    }

    @Override
    public List<String> supportedLanguages() {
        return List.of("java");
    }

    @Override
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        List<Issue> issues = new ArrayList<>();
        
        Path compiledClassesDir = ctx.getCompiledClassesDir();
        if (compiledClassesDir == null || !compiledClassesDir.toFile().exists()) {
            return issues; // No compiled classes to analyze
        }

        try {
            // Resolve backend's own classpath
            Path backendRoot = Paths.get(System.getProperty("user.dir"));
            String backendClasspath = classpathResolver.resolve(backendRoot);
            if (backendClasspath.isEmpty()) {
                log.warn("Could not resolve backend classpath for SpotBugs");
                return issues;
            }

            File tempXml = File.createTempFile("spotbugs-", ".xml");
            tempXml.deleteOnExit();

            List<String> command = new ArrayList<>();
            command.add("java");
            command.add("-cp");
            command.add(backendClasspath);
            command.add("edu.umd.cs.findbugs.FindBugs");
            command.add("-textui");
            command.add("-xml:withMessages");
            command.add("-output");
            command.add(tempXml.getAbsolutePath());
            command.add(compiledClassesDir.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                log.warn("SpotBugs analysis timed out after 120s");
                return issues;
            }

            issues.addAll(parseXml(tempXml, projectRoot));

        } catch (Exception e) {
            log.error("SpotBugs analysis failed: {}", e.getMessage(), e);
        }

        return issues;
    }

    private List<Issue> parseXml(File xmlFile, Path projectRoot) {
        List<Issue> issues = new ArrayList<>();
        if (!xmlFile.exists() || xmlFile.length() == 0) return issues;

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(xmlFile);
            
            NodeList bugInstances = doc.getElementsByTagName("BugInstance");
            for (int i = 0; i < bugInstances.getLength(); i++) {
                Element bugElement = (Element) bugInstances.item(i);
                
                String type = bugElement.getAttribute("type");
                String priorityStr = bugElement.getAttribute("priority");
                String category = bugElement.getAttribute("category");
                
                Element shortMessageEl = (Element) bugElement.getElementsByTagName("ShortMessage").item(0);
                String message = shortMessageEl != null ? shortMessageEl.getTextContent() : type;

                // Find SourceLine
                NodeList sourceLines = bugElement.getElementsByTagName("SourceLine");
                Element sourceLineEl = null;
                for (int j = 0; j < sourceLines.getLength(); j++) {
                    Element el = (Element) sourceLines.item(j);
                    if (el.getParentNode() == bugElement) {
                        sourceLineEl = el;
                        break;
                    }
                }

                String filePath = "";
                int line = 0;
                if (sourceLineEl != null) {
                    String sourcePath = sourceLineEl.getAttribute("sourcepath");
                    if (!sourcePath.isEmpty()) {
                        filePath = projectRoot.resolve("src/main/java").resolve(sourcePath).toAbsolutePath().toString();
                    }
                    String start = sourceLineEl.getAttribute("start");
                    if (!start.isEmpty()) {
                        line = Integer.parseInt(start);
                    }
                }

                Issue issue = new Issue(
                    "java",
                    IssueType.CODE_SMELL,
                    mapSeverity(priorityStr, category),
                    filePath,
                    line,
                    "SpotBugs (" + type + "): " + message
                );
                issues.add(issue);
            }
        } catch (Exception e) {
            log.error("Failed to parse SpotBugs XML: {}", e.getMessage());
        }
        return issues;
    }

    private Severity mapSeverity(String priority, String category) {
        if ("1".equals(priority)) return Severity.HIGH;
        if ("2".equals(priority)) return Severity.MEDIUM;
        return Severity.INFO;
    }
}

