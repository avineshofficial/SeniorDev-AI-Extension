# SeniorDev AI Extension

[![VS Code](https://img.shields.io/badge/VS%20Code-1.85%2B-blue.svg)](https://code.visualstudio.com/)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.3-blue.svg)](https://www.typescriptlang.org/)
[![License](https://img.shields.io/badge/License-MIT-brightgreen.svg)](#license)

**SeniorDev AI** is an intelligent, high-performance developer companion extension for Visual Studio Code. It combines instantaneous local compiler and linter diagnostics with an ultra-fast local LLM-powered auto-fix engine, providing rich inline diff annotations, instant whole-file corrections, and seamless accept/revert workflows.

---

## Key Features

### 1. 🔍 Diagnose File (Sub-200ms Static Analysis)
- **Zero-Noise Precision**: Pinpoints exact line errors and issues without clutter or irrelevant cross-language diagnostics.
- **Multi-Language Detection**:
  - **Python**: Compiles with `py_compile` and lints with `ruff` for instant syntax and logical error checking.
  - **Java**: Diagnostic checks with `javac` compiler detection.
- **Interactive Issue Panel**: Direct jump to erroneous lines in the active editor.

### 2. ⚡ Fix File (AI) — Whole-File Auto-Fix (~2.5s Latency)
- **High-Speed AI Fixes**: Powered by local LLM models (e.g., `qwen2.5-coder:3b` via Ollama).
- **100% Correct Output**: Generates complete, compilable replacement code with all missing imports, resolved syntax errors, and corrected variable references.
- **Resilient JSON Parsing**: Schema-optimized for rapid generation and fallback extraction.

### 3. 🎨 Native Inline Diff Annotations
- **In-Editor Color Coding**:
  - Newly added and modified lines receive subtle green background highlighting.
  - Removed and replaced lines receive clean, inline badges attached directly to the changed line: `🔴 - <removed code>`.
- **Overview Ruler Integration**: Green and red marks in the editor scrollbar for quick hunk navigation.
- **Sidebar Diff Viewer**: Clean `🟢 +` (added) and `🔴 -` (removed) breakdown with diff statistics.
- **Native Diff Viewer**: Click **Compare Diff (Native)** to open VS Code's side-by-side diff editor.

### 4. 🔄 Instant Accept & Revert Flow
- **Accept Fix**: Writes clean code to disk, clears annotations, and automatically re-diagnoses to confirm zero remaining issues.
- **Undo / Reject**: Instant rollback to original file backup with a single click.
- **Diagnose ↔ Fix Toggle**: Effortlessly toggle between diagnostic reports and AI fix views.

### 5. 📜 History & Session Tracking
- Preserves audit log of previous diagnose and fix runs per file with timestamps and issue counts.

---

## Architecture Overview

```
SeniorDev-AI-Extension/
├── seniordev-backend/          # Spring Boot 3 Java Service (Port 8080)
│   ├── src/main/java/com/seniordev/
│   │   ├── ai/                 # Ollama LLM Client & JSON Schema Engine
│   │   ├── api/                # REST Controllers (/api/analyze, /api/ai/fix-file)
│   │   ├── core/               # Project Scanner & Build Detection
│   │   ├── detectors/          # Modular Linters (Java, Python Ruff/Compile)
│   │   └── prompt/             # Optimized Prompt Templates
│   └── pom.xml
│
├── seniordev-ide-extension/    # VS Code IDE Extension
│   ├── src/
│   │   ├── api/                # HTTP Client for Backend Communication
│   │   ├── ui/                 # ChatViewProvider & Inline Diff Decorators
│   │   └── extension.ts        # Extension Activation & Command Registry
│   ├── package.json
│   └── tsconfig.json
│
└── README.md
```

---

## Prerequisites

Before running SeniorDev AI, ensure you have installed:

1. **Java 17+** and **Maven 3.8+**
2. **Node.js 18+** and **npm**
3. **Python 3.9+** (with `ruff` installed: `pip install ruff`)
4. **Ollama** running locally with the coding model:
   ```bash
   ollama pull qwen2.5-coder:3b
   ollama serve
   ```

---

## Getting Started

### 1. Start the Backend Server

```bash
cd seniordev-backend
mvn spring-boot:run
```
The backend starts at `http://localhost:8080`.

### 2. Build & Install the VS Code Extension

```bash
cd seniordev-ide-extension
npm install
npm run build
```

To package as a `.vsix` file:
```bash
npx vsce package --allow-missing-repository
code --install-extension seniordev-ide-extension-0.3.0.vsix
```

Alternatively, open the repository in VS Code and press **F5** to launch an Extension Development Host.

---

## Usage Guide

1. Open any Python or Java project in VS Code.
2. Open the **SeniorDev AI Assistant** view from the activity bar.
3. Click **Diagnose File** to view compiler and linter issues.
4. Click **Fix File (AI)** to let the AI automatically correct the file.
5. Review the diff and inline annotations (`🔴 - <code text>` / `🟢 +`).
6. Click **Accept Fix** to save or **Undo / Reject** to revert.

---

## Configuration

Settings can be customized in VS Code Settings (`Preferences > Settings > SeniorDev AI`):

| Setting | Default | Description |
|---|---|---|
| `seniordev.backendUrl` | `http://localhost:8080` | URL of the SeniorDev AI backend server |

---

## License

MIT License © 2026 [Avinesh](https://github.com/avineshofficial)
