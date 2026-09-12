package com.seniordev.core;

import java.nio.file.Path;
import java.util.List;

public interface IssueDetector {
    List<String> supportedLanguages();
    List<Issue> detect(Path projectRoot, BuildContext ctx);
}
