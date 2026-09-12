# SeniorDev-AI_Extension: Project Status & Roadmap

This document outlines the current state of development for the **SeniorDev-AI_Extension** project and details the roadmap for future development.

## 🟢 What is Developed Up To Now (Finished)

The project consists of three main components: a Spring Boot Backend, a VS Code Extension, and a sample test project.

### 1. Spring Boot Backend (`seniordev-backend`)
- **Core Architecture**: Developed a local multi-language code analysis backend.
- **Static Analysis Integrations**: 
  - Integrated **PMD** and **SpotBugs** for deep Java analysis.
  - Integrated **Ruff** for fast Python linting.
- **AI Integration**: Implemented `OllamaClient` and `OllamaAnalysisEnricher` to connect to local LLMs (via Ollama) for analyzing issues and generating automated fixes.
- **Safe Sandboxing**: Implemented a `SandboxWorkspace` and `FixVerifier` to securely compile and test AI-generated fixes before suggesting them.
- **REST APIs**: Exposed endpoints for Project Scanning (`AnalyzeRequest`), Fix Generation/Enrichment, and Verification.

### 2. VS Code Extension (`seniordev-ide-extension`)
- **Extension Infrastructure**: Set up a robust TypeScript-based extension targeting VS Code.
- **Core Commands**:
  - `seniordev.fixActiveFile`: Triggers an AI fix for the currently active file.
  - `seniordev.analyzeProject`: Scans the entire workspace for issues.
  - `seniordev.generateFixReport`: Generates a report/prompt for fixes.
- **User Interface (UI)**:
  - **Sidebar Views**: Developed the SeniorDev AI Activity Bar containing the Chat View and the Detected Issues Tree View.
  - **Webviews**: Configured webviews for interactive chatting and displaying project issue reports.
- **Configuration**: Added user settings to connect the extension to the local backend URL (`seniordev.backendUrl`).

### 3. Test Project (`test-project`)
- Created a playground environment containing purpose-built flawed files (`PmdViolation.java`, `SpotBugsViolation.java`, `test_python.py`) to validate the AI and static analysis pipelines.

---

## 🚀 What Next Needs to be Implemented (Balance Development)

To complete the project and make it production-ready, the following areas need to be developed:

### 1. VS Code UI Enhancements
- **Inline Diffs**: Implement VS Code native inline diffs to preview AI-generated code changes directly in the editor before accepting them.
- **Chat Interactivity**: Improve the `ChatViewProvider` to allow developers to ask free-form questions about the codebase and receive context-aware answers.
- **Click-to-Nav**: Ensure that clicking an issue in the `IssueTreeViewProvider` accurately navigates the user to the exact line and column in the file.

### 2. Backend & AI Improvements
- **Expanded Language Support**: Add detectors for JavaScript/TypeScript (ESLint/Prettier) and Go (golangci-lint).
- **Retrieval-Augmented Generation (RAG)**: Enhance the `OllamaClient` to index the entire codebase so the AI has global context when suggesting fixes, rather than just single-file context.
- **Cloud Fallback**: Add an optional cloud LLM fallback (e.g., OpenAI API or Gemini API) for users who cannot run Ollama locally or lack the necessary hardware.
- **Caching Mechanism**: Implement a caching layer for static analysis and AI responses to drastically speed up repetitive scans.

### 3. Stability & Testing
- **Unit and Integration Tests**: Write comprehensive test suites for backend APIs (`AnalysisController`) and extension logic.
- **CI/CD Pipeline**: Set up GitHub Actions to automatically build the Spring Boot JAR, compile the VS Code VSIX file, and run tests on every commit.
- **Telemetry & Logging**: Implement robust logging on both the extension and backend to track error rates and monitor AI token usage / response times.

### 4. Documentation & Release
- **User Guide**: Write a detailed usage guide in the `README.md` explaining how to install Ollama, run the backend, and install the VS Code `.vsix` extension.
- **Marketplace Publishing**: Prepare the VS Code extension for publication on the Microsoft Visual Studio Marketplace (update icons, add marketplace metadata).
