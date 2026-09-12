# SeniorDev AI — Local Multi-Language Co-Pilot Extension
## Revised Architecture (v2)

*A Java-based IDE extension that analyzes an entire project across languages, finds real issues, and uses a local Ollama model to explain, propose, and verify fixes. Fully offline, no API keys.*

> This is a revision of the original v1 spec, produced after a technical review. The core idea holds up — local backend, unified issue model, IDE panel, local LLM for explanation and fixes. What changes is *how* several pieces are implemented: a few were fragile enough to break on any real project, and one default was simply out of date. Section 1 is the short version; everything after it mirrors the original document's structure so you can diff section-by-section.

---

## Table of Contents

1. [Verdict: Is This Current?](#1-verdict-is-this-current)
2. [Overview](#2-overview)
3. [Goals & Non-Goals](#3-goals--non-goals)
4. [System Architecture](#4-system-architecture)
5. [Repository Structure](#5-repository-structure)
6. [Core Data Model](#6-core-data-model)
7. [Project Scanner & Build Context](#7-project-scanner--build-context)
8. [Language Detectors](#8-language-detectors)
9. [Prompt Generation](#9-prompt-generation)
10. [Ollama Integration](#10-ollama-integration)
11. [Verification Loop (New)](#11-verification-loop-new)
12. [REST API Design](#12-rest-api-design)
13. [IDE Extension Behavior](#13-ide-extension-behavior)
14. [Step-by-Step Development Plan](#14-step-by-step-development-plan)
15. [Configuration & Setup](#15-configuration--setup)
16. [Example Flows](#16-example-flows)
17. [Evaluation & Testing](#17-evaluation--testing)
18. [Where This Sits Relative to Your Final-Year Brief](#18-where-this-sits-relative-to-your-final-year-brief)
19. [Future Enhancements](#19-future-enhancements)

---

## 1. Verdict: Is This Current?

Short answer: the shape is right, the implementation detail needed work. Five things, in order of how much they'd have hurt:

1. **The compile detector would flood every real project with false errors.** Running bare `javac` on discovered files without the project's actual classpath means every third-party import fails with "cannot find symbol." This is the one that matters most — a real Java project always has dependencies, and v1's compile detector would have been unusable on anything but a single-file toy example.
2. **`codellama:7b` is a 2023 model.** Current small code-specialized models (the Qwen2.5-Coder / Qwen3-Coder family) score meaningfully better on code tasks at a comparable size, and one of them fits a 6GB-VRAM card about as well as codellama did.
3. **Hand-written regex rules were reinventing — badly — what PMD and SpotBugs already do well.** Both are mature, both are still actively maintained (PMD shipped version 7.21 in January 2026), and both are trivially embeddable as Java libraries.
4. **The AI response parser depended on the model following a markdown convention exactly.** Splitting on triple-backticks breaks the moment the model phrases its answer slightly differently. Ollama has supported schema-constrained JSON output via `/api/chat` for a while now — ask for `{explanation, fixCode, affectedFiles}` directly and skip the guesswork.
5. **Nothing verified that the AI's fix actually fixed anything.** "Apply Fix" would write straight from an LLM guess to disk. A verify step — apply to a sandbox copy, re-run the specific check that raised the issue, confirm it's clean — turns this from "suggests text" into "plans, applies, and checks its own work," which is both better engineering and a better demo.

Everything below folds those five fixes into the original structure. Nothing here is a rewrite of the concept — it's the same tool, built so it survives contact with a real codebase.

---

## 2. Overview

Working across a real project means compiler errors, linter warnings, and logic issues scattered across files and languages, and the existing tools don't look at the whole project at once or explain things in plain language. SeniorDev AI is a Java backend plus an IDE extension that:

- Scans every source file in the project, not just the open one
- Runs **real compilers and real static analyzers** — not regex approximations — to find compile errors, lint issues, and design/logic problems
- Sends the issues you choose to a local Ollama model, which explains them and proposes a fix
- **Verifies** the AI's fix against the actual tool that flagged the issue before ever offering to apply it
- Does all of this fully offline — no API keys, nothing leaves the machine

That scope is unchanged from v1. What changes is how compiling, detecting, prompting, and verifying are actually implemented.

---

## 3. Goals & Non-Goals

### Goals

- **Multi-file, multi-language analysis** — Java + Python for v1, extensible later
- **One unified `Issue` model** across every detector and language
- **Senior-dev-style guidance** per issue: explanation, focused fix, cross-file impact where relevant
- **Fully local, no API keys** — Ollama on the user's machine only
- **IDE integration** — VS Code first
- **Verify before offering to apply** *(new)* — every AI-suggested fix is checked against the tool that raised the original issue before the "Apply" action is enabled
- **Don't reimplement general-purpose static analysis** *(new)* — Java logic/design issues come from PMD and SpotBugs, Python issues come from Ruff. The project's own code exists for scanning, orchestration, prompting, and verification, not for rebuilding a linter from scratch.

### Non-Goals (for v1)

- Training or fine-tuning a model — pre-trained Ollama models only
- Supporting every language immediately — start with two
- Full automated refactoring across the whole codebase — localized, verified fixes first
- Cloud deployment or a multi-user server — single machine, local use

---

## 4. System Architecture

```text
+----------------------+          +----------------------------------+          +-----------------+
|  IDE Extension       |  HTTP    |  Java Backend (Spring Boot)       |  HTTP    |  Local Ollama    |
|  (VS Code, v1)       | <------> |                                    | <------> |  (localhost)    |
+----------------------+          +----------------------------------+          +-----------------+
                                       |                    |
                              resolves classpath,    calls PMD/SpotBugs
                              shells out to mvn/      (embedded) and
                              gradle for it           ruff (Python)
```

Flow:

1. User triggers analysis in the IDE.
2. IDE calls `POST /api/analyze` with the project root.
3. Backend detects the build system (Maven / Gradle / npm), **resolves the real classpath** for Java, applies ignore rules, then runs the compiler plus PMD / SpotBugs / Ruff and returns `List<Issue>`.
4. User selects which issues to send to AI — not "all of them" by default, since local inference is the slow part.
5. IDE calls `POST /api/enrich` with the selected issue IDs; the backend streams results back as each one finishes.
6. For any fix the user wants to trust, the IDE calls `POST /api/verify`; the backend applies the fix to a sandboxed copy, re-runs the specific check, and reports pass/fail.
7. Only a verified fix is offered as a one-click "Apply to file."

IntelliJ is intentionally not in this diagram — it's a different plugin platform entirely (IntelliJ Platform SDK, Kotlin/Java) and belongs in Future Enhancements rather than the v1 phase plan.

---

## 5. Repository Structure

### Backend (`seniordev-backend`)

```text
seniordev-backend/
  src/main/java/com/seniordev/
    core/
      Issue.java
      IssueType.java
      Severity.java
      VerificationStatus.java        (NEW)
      IssueDetector.java
      ProjectScanner.java
      BuildContext.java
      IgnoreRules.java                (NEW — .gitignore + default excludes)
    build/                             (NEW package)
      BuildSystem.java                 (enum: MAVEN, GRADLE, NPM, UNKNOWN)
      ClasspathResolver.java
    detectors/
      java/
        JavaCompileDetector.java       (revised — Compiler API, resolved classpath)
        JavaPmdDetector.java           (NEW — replaces JavaLogicDetector)
        JavaSpotBugsDetector.java      (NEW)
        JavaTestDetector.java
      python/
        PythonRuffDetector.java        (renamed/revised — replaces PythonLintDetector)
        PythonPylintDetector.java      (optional deeper pass)
        PythonTestDetector.java
      typescript/                       (phase 2, unchanged scope from v1)
        TypeScriptCompileDetector.java
        TypeScriptLintDetector.java
    prompt/
      PromptGenerator.java
      PromptTemplate.java
      ContextBuilder.java              (NEW — pulls cross-file symbol context)
    ai/
      OllamaClient.java                (revised — /api/chat, structured output)
      FixResult.java                   (NEW — replaces FixParser entirely)
    verify/                             (NEW package)
      FixVerifier.java
      SandboxWorkspace.java
    api/
      AnalysisController.java
      dto/
        AnalyzeRequest.java
        AnalyzeResponse.java
        EnrichRequest.java              (gains issueIds)
        VerifyRequest.java              (NEW)
      AnalysisStore.java                (NEW — in-memory session store, no DB needed)
    config/
      ModelConfig.java
      AnalysisProperties.java           (NEW — ignore patterns, model name, etc.)
  src/main/resources/
    application.properties
  pom.xml
```

### IDE Extension (`seniordev-ide-extension` — VS Code)

Unchanged in shape from v1:

```text
seniordev-ide-extension/
  src/
    extension.ts
    client/backendClient.ts        (gains SSE handling for /enrich)
    ui/IssueTreeViewProvider.ts    (adds selection + verification badges)
    ui/IssueDetailPanel.ts         (adds Verify button)
    types.ts
  package.json
  tsconfig.json
```

---

## 6. Core Data Model

`IssueType` and `Severity` are unchanged from v1. One new enum:

```java
public enum VerificationStatus {
    NOT_VERIFIED,
    VERIFIED_FIXED,
    VERIFICATION_FAILED,
    VERIFICATION_SKIPPED   // detector doesn't support an isolated re-check yet
}
```

`Issue.java` gains two fields on top of the v1 set (`id`, `language`, `type`, `severity`, `filePath`, `line`, `message`, `snippet`, `suggestion`, `aiExplanation`, `aiFixCode`):

```java
private VerificationStatus verificationStatus = VerificationStatus.NOT_VERIFIED;
private List<String> relatedFiles = new ArrayList<>();
private String verificationDetail;   // populated on VERIFICATION_FAILED — the new diagnostic
```

`FixResult.java` (**new — replaces `FixParser`**):

```java
public record FixResult(
    String explanation,
    String fixCode,
    List<String> affectedFiles,
    String confidence   // "low" | "medium" | "high" — a model self-report, treat as a hint
) {}
```

This record's shape *is* the JSON Schema sent to Ollama's `format` parameter (see Section 10) — Jackson deserializes the model's response straight into it. There's no separate parsing step to get wrong.

---

## 7. Project Scanner & Build Context

`BuildContext` gains:

```java
private BuildSystem buildSystem;        // MAVEN, GRADLE, NPM, UNKNOWN
private String resolvedClasspath;       // Java only; empty if not Maven/Gradle
```

`ProjectScanner` responsibilities (revised):

- Recursively find source files, but **skip anything matched by `.gitignore`** (if present) plus a hardcoded default list: `node_modules/, target/, build/, dist/, .git/, venv/, .venv/, __pycache__/, .idea/, .vscode/`. Scanning a real project without this picks up hundreds of megabytes of generated and dependency files and slows down every later step. (Ruff already respects `.gitignore` on its own; this matters most for the file discovery that feeds the Java side.)
- Detect the build system from marker files: `pom.xml` → Maven, `build.gradle` / `build.gradle.kts` → Gradle, `package.json` → npm/TS.
- **For Maven projects, resolve the real compile classpath** — shell out to `mvn -q dependency:build-classpath -Dmdep.outputFile=<tmp>` and read the file it writes, or use the Maven Invoker API to stay in-process. For Gradle, a small custom task (or the Gradle Tooling API) does the same job.
- This resolved classpath is what makes `JavaCompileDetector` usable on a real project — see below.

---

## 8. Language Detectors

`IssueDetector` interface is unchanged:

```java
public interface IssueDetector {
    List<String> supportedLanguages();
    List<Issue> detect(Path projectRoot, BuildContext ctx);
}
```

### `JavaCompileDetector` (revised)

Use the JDK's Compiler API instead of shelling out to `javac` and regex-parsing stderr — structured diagnostics instead of text that changes shape between JDK versions, and, critically, a real classpath:

```java
public class JavaCompileDetector implements IssueDetector {

    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            List<File> sources = findJavaFiles(projectRoot); // respects IgnoreRules
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);

            List<String> options = List.of(
                "-classpath", ctx.getResolvedClasspath(),      // <-- the actual fix
                "-Xlint:all", "-proc:none",
                "-d", Files.createTempDirectory("seniordev-out").toString()
            );

            compiler.getTask(null, fm, diagnostics, options, null, units).call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return diagnostics.getDiagnostics().stream()
            .filter(d -> d.getSource() != null)
            .map(this::toIssue)
            .toList();
    }

    private Issue toIssue(Diagnostic<? extends JavaFileObject> d) {
        Issue issue = new Issue();
        issue.setLanguage("java");
        issue.setType(IssueType.COMPILE_ERROR);
        issue.setSeverity(d.getKind() == Diagnostic.Kind.ERROR ? Severity.CRITICAL : Severity.MEDIUM);
        issue.setFilePath(d.getSource().toUri().getPath());
        issue.setLine((int) d.getLineNumber());
        issue.setMessage(d.getMessage(Locale.ROOT));
        return issue;
    }
}
```

### `JavaPmdDetector` / `JavaSpotBugsDetector` (replace `JavaLogicDetector`)

Embed PMD as a library rather than shelling out — it's called repeatedly from an IDE, so process-startup latency adds up:

```java
public class JavaPmdDetector implements IssueDetector {
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        PMDConfiguration config = new PMDConfiguration();
        config.setDefaultLanguageVersion(LanguageRegistry.PMD.getLanguageVersionById("java", "21"));
        config.addRuleSet("rulesets/java/quickstart.xml");   // start here, tune later
        config.setInputPathList(List.of(projectRoot));

        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            Report report = pmd.performAnalysisAndCollectReport();
            return report.getViolations().stream().map(this::toIssue).toList();
        }
    }
    // toIssue(RuleViolation): file, line, description, rule priority -> Issue
    // (check PMD's current embedding API docs for exact method names — this sketches the shape)
}
```

SpotBugs reads bytecode, not source, so it runs after a successful compile, either via its Ant/Maven-plugin API or by shelling out to the `spotbugs` CLI against the `-d` output directory the compile step already produced.

### `PythonRuffDetector` (replaces `PythonLintDetector`)

```java
public class PythonRuffDetector implements IssueDetector {
    public List<Issue> detect(Path projectRoot, BuildContext ctx) {
        var proc = new ProcessBuilder("ruff", "check", "--output-format=json", ".")
            .directory(projectRoot.toFile())
            .start();
        String json = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor(30, TimeUnit.SECONDS);

        List<RuffViolation> violations = objectMapper.readValue(json, new TypeReference<>() {});
        return violations.stream().map(this::toIssue).toList();
    }
}
```

Ruff's JSON already carries file, row, column, rule code, and message per violation, so the mapping to `Issue` is close to a rename, not a parse. Keep `PythonPylintDetector` as an optional pass behind a config flag if you want Pylint's deeper type-aware checks — Ruff alone covers most of what a project needs, much faster.

### TypeScript detectors

Unchanged in spirit from v1 (`tsc --noEmit` for compile errors, ESLint for lint) and still out of v1 scope — that de-scoping was reasonable and worth keeping. If picked up later: ESLint 9's flat config (`eslint.config.js`) is now the default, and Biome is worth evaluating as a faster alternative, though ESLint's plugin ecosystem is still broader.

---

## 9. Prompt Generation

Split into system + user messages for `/api/chat` instead of one flattened string, and add a lightweight `ContextBuilder` for cross-file relevance.

**System message** (sent once per session, not repeated per issue):

~~~text
You are a senior developer reviewing a {language} project. For each issue,
respond only with JSON matching the given schema: a plain-English explanation
(2-4 sentences), a minimal corrected code snippet, and a list of any other
files that likely need related changes. Do not include markdown fences or
any text outside the JSON object.
~~~

**User message** per issue: file, line, issue type/severity, the original tool message, the code snippet, plus — new — one to three short excerpts from `ContextBuilder` when the issue references a symbol declared elsewhere (e.g. "cannot find symbol: variable X" → pull X's declaration if it's found elsewhere in the scanned project). Keep these excerpts to a few lines each, not whole files — context tokens are the scarce resource on a 6GB card.

Since the response shape is now enforced by Ollama's `format` schema rather than requested in prose, `PromptTemplate` shrinks to the issue-specific instructions — the "respond only with JSON" contract lives in code, not in hope that the model reads carefully.

---

## 10. Ollama Integration

### Model choice

`ollama pull qwen2.5-coder:7b` — a current, code-specialized small model that, at its default quantization (~4.7 GB), fits a 6GB-VRAM card with headroom for a modest context window. This is a fast-moving space; if a clearly stronger small code model has landed by the time you're building, swapping it is a one-line config change (`ollama.model`) — nothing else in the design depends on which model you pick. Two practical notes for a 6GB card specifically: keep `num_ctx` modest (4096 is a reasonable starting point — raise it only after checking VRAM headroom), and run `ollama ps` after pulling to confirm the model is fully GPU-resident; any CPU offload slows generation noticeably.

### `OllamaClient` (revised)

`/api/chat`, not `/api/generate` — role-based messages instead of one flattened string, Jackson-serialized DTOs instead of hand-built JSON strings, and the `format` parameter carrying `FixResult`'s JSON Schema so the response deserializes directly:

```java
public class OllamaClient {
    private final String baseUrl;   // from config
    private final String model;     // from config, default "qwen2.5-coder:7b"
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public FixResult generateFix(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        var body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "stream", false,
            "format", FIX_RESULT_SCHEMA,           // built once from FixResult's shape
            "options", Map.of("num_ctx", 4096)
        );

        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());
        String content = root.at("/message/content").asText();
        return mapper.readValue(content, FixResult.class);   // direct deserialize — no FixParser
    }

    public boolean isAvailable() {
        // GET /api/tags, confirm the configured model is present — backs /api/health
    }
}
```

`FixParser.java` is gone — the schema does what it used to do, more reliably.

---

## 11. Verification Loop (New)

The piece that turns "AI suggests text" into "AI proposes, the system checks, the user decides":

```java
public class FixVerifier {

    public VerificationResult verify(Issue issue, String fixCode) {
        Path sandbox = SandboxWorkspace.copyProjectSubset(issue); // just the affected file(s)
        SandboxWorkspace.applyFix(sandbox, issue.getFilePath(), fixCode);

        return switch (issue.getType()) {
            case COMPILE_ERROR -> recompileSingleFile(sandbox, issue);
            case LINT_WARNING, CODE_SMELL, DESIGN_ISSUE, LOGIC_ISSUE -> rerunSameDetectorRule(sandbox, issue);
            case TEST_FAILURE -> rerunNearestTest(sandbox, issue);   // optional / stretch
        };
    }
}
```

`SandboxWorkspace` copies only the file(s) the fix touches into a temp directory (plus, for Java, the resolved classpath needed to make the check meaningful), applies the patch there, and never writes to the user's real file. `FixVerifier` then re-runs **only the specific detector rule that raised the original issue** — not a full project rescan, which would be far too slow to do per-fix on this hardware — and returns `VERIFIED_FIXED`, `VERIFICATION_FAILED` (with the new diagnostic attached, so the user can see why the AI's attempt didn't work), or `VERIFICATION_SKIPPED` for issue types without an isolated re-check yet.

Only `VERIFIED_FIXED` unlocks the one-click "Apply to file" action in the IDE. Everything else still shows the suggestion — the user can always apply it manually — but it's visibly flagged as unverified rather than presented with the same confidence as a checked fix.

---

## 12. REST API Design

```java
@RestController
@RequestMapping("/api")
public class AnalysisController {

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest req) {
        // unchanged in shape from v1 — scan, resolve classpath, run detectors, return issues
    }

    @GetMapping("/health")
    public HealthStatus health() {
        // checks: Ollama reachable + configured model present, mvn/gradle on PATH, ruff on PATH
        // returns a status per dependency so the IDE can show "Ollama not running" instead of a stack trace
    }

    @PostMapping("/enrich")
    public SseEmitter enrich(@RequestBody EnrichRequest req) {
        // req.issueIds() — a SUBSET the user picked, not implicitly "all issues"
        // emits one event per completed issue as it finishes, instead of blocking on the whole batch
    }

    @PostMapping("/verify")
    public VerificationResult verify(@RequestBody VerifyRequest req) {
        return fixVerifier.verify(req.issue(), req.fixCode());
    }

    @GetMapping("/issues")
    public List<Issue> listIssues(@RequestParam String sessionId) {
        return analysisStore.get(sessionId); // in-memory map keyed by session/project root — no DB needed for v1
    }
}
```

`EnrichRequest` gains `List<String> issueIds`. `AnalyzeRequest` / `AnalyzeResponse` are unchanged from v1.

---

## 13. IDE Extension Behavior

1. Open project → "Analyze Project with SeniorDev AI" → `POST /analyze`.
2. Tree view groups issues by file; user **selects** which ones to enrich (not everything by default) → `POST /enrich`, results stream in one at a time via SSE so the panel fills in progressively instead of sitting blank.
3. Click an issue → detail panel shows explanation + diff. "Verify Fix" calls `/verify`; a badge shows verified / failed / skipped.
4. "Apply to File" is the default action once verified; still available — with a visible "unverified" warning — if the user wants to apply anyway.

VS Code is the only v1 target; see Future Enhancements for IntelliJ.

---

## 14. Step-by-Step Development Plan

Rough month markers assume 1–3 people over 4–6 months.

**Phase 1 (~Weeks 1–3) — A backend that doesn't lie**
1. Spring Boot scaffold.
2. `Issue` / `IssueType` / `Severity` / `BuildContext`.
3. `ProjectScanner` with ignore rules + build-system detection.
4. `ClasspathResolver` for Maven (Gradle can wait for Phase 2).
5. `JavaCompileDetector` via the Compiler API.
6. `/analyze` returning real compile issues.

*Test target:* a small Maven project with (a) an intentional error and (b) a real third-party dependency (Gson or Jackson) used correctly elsewhere. If classpath resolution is missing, the Gson/Jackson usage throws false errors too — that's the v1 bug, and confirming it's gone here is worth doing before moving on.

**Phase 2 (~Weeks 4–6) — Real static analysis**
7. `JavaPmdDetector` (embedded PMD, `quickstart.xml` to start).
8. `JavaSpotBugsDetector` (post-compile bytecode pass).
9. `PythonRuffDetector` (`ruff check --output-format=json`).
10. Gradle support for classpath + build detection, if time allows.

**Phase 3 (~Weeks 7–9) — Prompting and Ollama**
11. `PromptTemplate` + `PromptGenerator` (system/user split).
12. `ContextBuilder` for cross-file symbol lookups (grep-based is fine for v1).
13. `OllamaClient` on `/api/chat` with the `FixResult` JSON Schema.
14. `/enrich` (synchronous first; add SSE once the round trip works end-to-end).

*Test:* `ollama pull qwen2.5-coder:7b`, `ollama serve`, run `/analyze` then `/enrich`, confirm the response deserializes into `FixResult` with no manual parsing.

**Phase 4 (~Weeks 10–12) — Verification loop**
15. `SandboxWorkspace` (temp-copy + patch apply).
16. `FixVerifier` for `COMPILE_ERROR` and the PMD/Ruff-backed types.
17. `/verify` endpoint.
18. `VerificationStatus` wired into `Issue` and the `/enrich` response.

Protect time for this phase — it's the piece that most changes how the finished tool reads.

**Phase 5 (~Weeks 13–16) — IDE Extension**
19. VS Code scaffold, `seniordev.analyzeProject` command.
20. `backendClient.ts` for `/analyze`, `/enrich` (SSE), `/verify`.
21. Tree view with grouping + selection.
22. Detail webview: explanation, diff, Verify button, Apply button gated on verification.

**Phase 6 (~Weeks 17–20) — Polish**
23. `/health` endpoint + "Ollama not running" UX.
24. Response caching (hash of prompt → cached `FixResult`) so repeated issue signatures skip the model.
25. Project health summary, severity/language filters.
26. Report writeup + demo project prepared.

*Stretch, if time remains:* TypeScript detectors, test-aware verification, IntelliJ plugin skeleton.

---

## 15. Configuration & Setup

### `application.properties`

```properties
server.port=8080

ollama.base-url=http://localhost:11434
ollama.model=qwen2.5-coder:7b
ollama.num-ctx=4096
ollama.timeout-seconds=120

analysis.ignore-patterns=node_modules/**,target/**,build/**,dist/**,.git/**,venv/**,.venv/**,__pycache__/**,.idea/**,.vscode/**
analysis.pmd-ruleset=rulesets/java/quickstart.xml
```

### Ollama setup (user machine)

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama pull qwen2.5-coder:7b
ollama serve
ollama ps   # confirm the model is fully GPU-resident before trusting latency numbers
```

If VRAM is tighter than expected once the IDE, backend, and Ollama are all running together, a smaller quant or a lighter code model is the fallback — one config line, nothing else in the design changes.

### IDE Extension config

Unchanged from v1 — the `package.json` commands/views block is still accurate.

---

## 16. Example Flows

**Flow 1 — Analyze:** unchanged in shape from v1 — `POST /analyze { projectRoot }`, response is a tree of issues. The difference is what's behind it: a resolved-classpath compile plus PMD/SpotBugs, so a project with real dependencies doesn't drown in false positives.

**Flow 2 — Enrich, revised:** user selects 3 of 12 detected issues → `POST /enrich { issueIds: [...] }` → results stream back one at a time as JSON matching `FixResult` (no fence-parsing) → user clicks "Verify Fix" on one → `POST /verify` recompiles just that file in a sandbox → `VERIFIED_FIXED` → "Apply to file" lights up.

---

## 17. Evaluation & Testing

**Functional:** sample projects with known compile errors and known logic issues; confirm detection, relevant explanations, and fixes that compile/resolve for simple cases.

**Manual:** 3–5 developers use the extension on a small project and rate clarity, usefulness, and "senior dev feel" (1–5 each) — unchanged from v1.

**Metrics** (v1's list cut off mid-sentence here — completed):
- Issues detected per project, broken down by detector and type
- **Percentage of AI-suggested fixes that pass verification on the first attempt** — the metric v1 was missing; without it there's no way to say the tool works, only that it produces plausible-looking text
- Average verification round-trip time (sandbox compile/re-lint latency)
- Average end-to-end `/enrich` latency per issue, measured on the actual target hardware class (a 6GB-VRAM laptop, not a workstation) — report this honestly; it's more convincing than a bigger number from different hardware
- Developer ratings, as above

---

## 18. Where This Sits Relative to Your Final-Year Brief

Worth flagging since it lines up closely: a Java 21+/Spring Boot backend and a software-only, laptop-demoable build both match what you've been screening final-year candidates against. One thing worth being deliberate about — a local AI coding assistant is a common shape of project on GitHub (Continue, Cline, Tabby, and a long tail of Ollama+VS Code hobby extensions all live in roughly this space), which is exactly the category your own criteria steer away from. The Verification Loop and the cross-file `ContextBuilder` are what separate this from "a linter wrapped around a chatbot" — they're the plan → apply → check → adapt loop, not just a suggestion box. If this is the one you run with, build and demo those two pieces first rather than treating them as Phase 4 polish.

---

## 19. Future Enhancements

- **IntelliJ plugin** — a separate codebase on the IntelliJ Platform SDK (Kotlin/Java, not TypeScript); different enough from the VS Code extension that it's realistically its own project, not a v1 stretch goal
- **TypeScript detectors** (`tsc` + ESLint, or Biome)
- **Test-aware verification** — auto-run the nearest unit test after a fix, not just recompile/re-lint
- **Incremental analysis** — file-watch and re-scan only changed files, instead of a full rescan on every trigger
- **Cross-session response caching** — same issue signature reuses a cached `FixResult`
- **Optional cloud-model fallback** for issues local inference handles poorly — opt-in only; fully local stays the core promise
