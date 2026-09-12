import * as vscode from 'vscode';
import * as path from 'path';
import { Issue, WebviewMessage } from '../types';

export class ProjectReportPanel {
  public static currentPanel: ProjectReportPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposables: vscode.Disposable[] = [];
  private _issues: Issue[] = [];
  private _projectRoot: string = '';
  private _scopeTitle: string = 'Entire Project';

  private constructor(
    panel: vscode.WebviewPanel,
    extensionUri: vscode.Uri,
    issues: Issue[],
    projectRoot: string,
    scopeTitle: string,
    private readonly onMessageCallback: (msg: WebviewMessage, panel: ProjectReportPanel) => void
  ) {
    this._panel = panel;
    this._extensionUri = extensionUri;
    this._issues = issues;
    this._projectRoot = projectRoot;
    this._scopeTitle = scopeTitle;

    this.update();

    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

    this._panel.webview.onDidReceiveMessage(
      (message: WebviewMessage) => {
        this.onMessageCallback(message, this);
      },
      null,
      this._disposables
    );
  }

  public static createOrShow(
    extensionUri: vscode.Uri,
    issues: Issue[],
    projectRoot: string,
    scopeTitle: string,
    onMessage: (msg: WebviewMessage, panel: ProjectReportPanel) => void
  ): ProjectReportPanel {
    const column = vscode.ViewColumn.One;

    if (ProjectReportPanel.currentPanel) {
      ProjectReportPanel.currentPanel._issues = issues;
      ProjectReportPanel.currentPanel._projectRoot = projectRoot;
      ProjectReportPanel.currentPanel._scopeTitle = scopeTitle;
      ProjectReportPanel.currentPanel._panel.reveal(column);
      ProjectReportPanel.currentPanel.update();
      return ProjectReportPanel.currentPanel;
    }

    const panel = vscode.window.createWebviewPanel(
      'seniordevProjectReport',
      `SeniorDev AI Report (${scopeTitle})`,
      column,
      {
        enableScripts: true,
        localResourceRoots: [extensionUri]
      }
    );

    ProjectReportPanel.currentPanel = new ProjectReportPanel(
      panel,
      extensionUri,
      issues,
      projectRoot,
      scopeTitle,
      onMessage
    );
    return ProjectReportPanel.currentPanel;
  }

  public setIssues(issues: Issue[], scopeTitle?: string): void {
    this._issues = issues;
    if (scopeTitle) {
      this._scopeTitle = scopeTitle;
    }
    this.update();
  }

  public getIssues(): Issue[] {
    return this._issues;
  }

  public getProjectRoot(): string {
    return this._projectRoot;
  }

  private update(): void {
    this._panel.title = `SeniorDev AI Report (${this._scopeTitle}) - ${this._issues.length} Issues`;
    this._panel.webview.html = this._getHtmlForWebview();
  }

  public generateMarkdownPrompt(): string {
    const lines: string[] = [];
    lines.push(`# SeniorDev AI Analysis & Fix Request`);
    lines.push(`**Scope:** ${this._scopeTitle}`);
    lines.push(`**Project Root:** \`${this._projectRoot}\``);
    lines.push(`**Total Issues:** ${this._issues.length}`);
    lines.push(``);
    lines.push(`Please provide complete, accurate, senior-level code fixes and explanations for the following static analysis issues detected in this project:\n`);

    this._issues.forEach((issue, idx) => {
      lines.push(`---`);
      lines.push(`### Issue #${idx + 1}: ${issue.message}`);
      lines.push(`- **File:** \`${issue.filePath}:${issue.line}\``);
      lines.push(`- **Language:** ${issue.language} | **Type:** ${issue.type} | **Severity:** ${issue.severity}`);
      if (issue.snippet) {
        lines.push(`\n**Original Code Snippet:**`);
        lines.push(`\`\`\`${issue.language.toLowerCase()}`);
        lines.push(issue.snippet);
        lines.push(`\`\`\``);
      }
      if (issue.aiExplanation) {
        lines.push(`\n**Current AI Explanation:** ${issue.aiExplanation}`);
      }
      if (issue.aiFixCode) {
        lines.push(`\n**Current Proposed Fix:**`);
        lines.push(`\`\`\`${issue.language.toLowerCase()}`);
        lines.push(issue.aiFixCode);
        lines.push(`\`\`\``);
      }
      lines.push(``);
    });

    lines.push(`---\n**Instructions for AI Assistant:**`);
    lines.push(`1. Explain the root cause of each issue above.`);
    lines.push(`2. Provide complete, drop-in replacement code for each file that resolves the errors without introducing new syntax, build, or linter errors.`);
    lines.push(`3. Preserve existing functionality and code style.`);

    return lines.join('\n');
  }

  private escapeHtml(text: string | null | undefined): string {
    if (!text) {
      return '';
    }
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  private _getHtmlForWebview(): string {
    const criticalCount = this._issues.filter(i => i.severity === 'CRITICAL' || i.severity === 'HIGH').length;
    const mediumCount = this._issues.filter(i => i.severity === 'MEDIUM').length;
    const verifiedCount = this._issues.filter(i => i.verificationStatus === 'VERIFIED_FIXED').length;
    const enrichedCount = this._issues.filter(i => !!i.aiExplanation).length;

    const issuesJsonEscaped = this.escapeHtml(JSON.stringify(this._issues, null, 2));

    const issueCardsHtml = this._issues.map((issue, idx) => {
      const isEnriched = !!issue.aiExplanation;
      const isVerified = issue.verificationStatus === 'VERIFIED_FIXED';
      const isFailed = issue.verificationStatus === 'VERIFICATION_FAILED';

      let statusBadge = '<span class="badge badge-gray">Not Verified</span>';
      if (isVerified) {
        statusBadge = '<span class="badge badge-success">VERIFIED FIXED</span>';
      } else if (isFailed) {
        statusBadge = '<span class="badge badge-error">VERIFICATION FAILED</span>';
      }

      return `
      <div class="issue-card" id="card-${issue.id}">
        <div class="issue-card-header">
          <div class="issue-title-group">
            <span class="issue-number">#${idx + 1}</span>
            <span class="badge badge-${this.escapeHtml(issue.severity)}">${this.escapeHtml(issue.severity)}</span>
            <span class="badge badge-lang">${this.escapeHtml(issue.language)}</span>
            <span class="badge badge-type">${this.escapeHtml(issue.type)}</span>
            ${statusBadge}
          </div>
          <div class="issue-message">${this.escapeHtml(issue.message)}</div>
          <div class="issue-location" onclick="openFile('${this.escapeHtml(issue.filePath)}', ${issue.line})">
            ?? ${this.escapeHtml(issue.filePath)} : Line ${issue.line} ?
          </div>
        </div>

        ${issue.snippet ? `
        <div class="code-section">
          <div class="code-title">Original Snippet (Line ${issue.line})</div>
          <pre><code>${this.escapeHtml(issue.snippet)}</code></pre>
        </div>
        ` : ''}

        ${isEnriched ? `
        <div class="ai-section">
          <div class="ai-title">?? Senior Dev Explanation</div>
          <div class="ai-text">${this.escapeHtml(issue.aiExplanation)}</div>
        </div>

        ${issue.aiFixCode ? `
        <div class="code-section code-section-fix">
          <div class="code-title">? Proposed Fix Code</div>
          <pre><code>${this.escapeHtml(issue.aiFixCode)}</code></pre>
        </div>
        ` : ''}

        ${issue.verificationDetail ? `
        <div class="detail-section">
          <div class="detail-title">??? Verification Details</div>
          <div class="detail-text">${this.escapeHtml(issue.verificationDetail)}</div>
        </div>
        ` : ''}
        ` : `
        <div class="ai-placeholder">
          <span>AI fix not yet generated for this issue.</span>
          <button class="btn-sm" onclick="enrichSingle('${issue.id}')">? Generate AI Fix</button>
        </div>
        `}

        <div class="card-actions">
          <button class="btn-sm btn-outline" onclick="copySinglePrompt('${issue.id}')">?? Copy Single Prompt</button>
          <button class="btn-sm btn-secondary" onclick="verifySingle('${issue.id}')">? Verify in Sandbox</button>
          <button class="btn-sm ${isVerified ? 'btn-success' : ''}" onclick="applySingle('${issue.id}', '${this.escapeHtml(issue.filePath)}')">
            ${isVerified ? '? Apply Fix' : '?? Apply Fix'}
          </button>
        </div>
      </div>
      `;
    }).join('\n');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SeniorDev AI Analysis Report</title>
  <style>
    :root {
      --font-family: var(--vscode-font-family, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif);
      --bg: var(--vscode-editor-background, #1e1e1e);
      --fg: var(--vscode-editor-foreground, #d4d4d4);
      --card-bg: var(--vscode-sideBar-background, #252526);
      --border: var(--vscode-panel-border, #3c3c3c);
      --accent: var(--vscode-button-background, #007acc);
      --accent-hover: var(--vscode-button-hoverBackground, #0062a3);
      --success: #388e3c;
      --error: #d32f2f;
      --warning: #f57c00;
      --info: #0288d1;
    }
    body {
      font-family: var(--font-family);
      background-color: var(--bg);
      color: var(--fg);
      padding: 24px;
      line-height: 1.5;
      margin: 0;
    }
    .top-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      border-bottom: 1px solid var(--border);
      padding-bottom: 16px;
      margin-bottom: 20px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .header-title h1 {
      margin: 0 0 4px 0;
      font-size: 22px;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .header-subtitle {
      font-size: 13px;
      color: #888;
    }
    .global-actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
    .metrics-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 16px;
      margin-bottom: 24px;
    }
    .metric-card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 14px 18px;
      display: flex;
      flex-direction: column;
    }
    .metric-number {
      font-size: 26px;
      font-weight: 700;
      margin-top: 4px;
    }
    .metric-label {
      font-size: 12px;
      color: #888;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .metric-critical { border-left: 4px solid var(--error); }
    .metric-verified { border-left: 4px solid var(--success); }
    .metric-enriched { border-left: 4px solid #ab47bc; }
    .metric-total { border-left: 4px solid var(--accent); }

    .issue-card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 16px;
      margin-bottom: 18px;
      transition: border-color 0.2s;
    }
    .issue-card:hover {
      border-color: #555;
    }
    .issue-card-header {
      margin-bottom: 12px;
    }
    .issue-title-group {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 6px;
      flex-wrap: wrap;
    }
    .issue-number {
      font-weight: bold;
      color: #888;
      font-size: 13px;
    }
    .badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 7px;
      border-radius: 4px;
      text-transform: uppercase;
      letter-spacing: 0.4px;
    }
    .badge-CRITICAL, .badge-HIGH { background: #d32f2f33; color: #ff6b6b; border: 1px solid #ff6b6b; }
    .badge-MEDIUM { background: #f57c0033; color: #ffa726; border: 1px solid #ffa726; }
    .badge-LOW, .badge-INFO { background: #0288d133; color: #4fc3f7; border: 1px solid #4fc3f7; }
    .badge-lang { background: #33333388; color: #ccc; border: 1px solid #555; }
    .badge-type { background: #6200ea22; color: #b388ff; border: 1px solid #7c4dff; }
    .badge-success { background: var(--success); color: #fff; }
    .badge-error { background: var(--error); color: #fff; }
    .badge-gray { background: #444; color: #bbb; }

    .issue-message {
      font-size: 15px;
      font-weight: 600;
      margin: 4px 0;
    }
    .issue-location {
      font-size: 12px;
      color: var(--vscode-textLink-foreground, #3794ff);
      cursor: pointer;
      display: inline-block;
      margin-top: 2px;
    }
    .issue-location:hover {
      text-decoration: underline;
    }

    .code-section {
      border: 1px solid var(--border);
      border-radius: 4px;
      margin: 10px 0;
      overflow: hidden;
    }
    .code-section-fix {
      border-color: #388e3c44;
    }
    .code-title {
      font-size: 11px;
      font-weight: 700;
      padding: 4px 10px;
      background: #00000033;
      border-bottom: 1px solid var(--border);
      color: #aaa;
    }
    .code-section-fix .code-title {
      background: #388e3c22;
      color: #b9f6ca;
      border-bottom: 1px solid #388e3c44;
    }
    pre {
      margin: 0;
      padding: 10px 12px;
      font-family: var(--vscode-editor-font-family, Consolas, monospace);
      font-size: 12px;
      overflow-x: auto;
      background: #00000022;
    }

    .ai-section {
      background: #6200ea11;
      border: 1px solid #7c4dff33;
      border-radius: 4px;
      padding: 12px;
      margin: 10px 0;
    }
    .ai-title {
      font-size: 12px;
      font-weight: 700;
      color: #b388ff;
      margin-bottom: 6px;
    }
    .ai-text {
      font-size: 13px;
      white-space: pre-wrap;
      line-height: 1.6;
    }
    .ai-placeholder {
      font-size: 12px;
      color: #888;
      background: #00000022;
      padding: 10px 14px;
      border-radius: 4px;
      margin: 10px 0;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .detail-section {
      background: #00000033;
      border-left: 3px solid var(--border);
      padding: 8px 12px;
      margin: 10px 0;
      font-size: 12px;
    }
    .detail-title {
      font-weight: 700;
      margin-bottom: 4px;
    }
    .detail-text {
      font-family: monospace;
      white-space: pre-wrap;
    }

    .card-actions {
      display: flex;
      gap: 8px;
      margin-top: 12px;
      flex-wrap: wrap;
    }
    button {
      background-color: var(--accent);
      color: #ffffff;
      border: none;
      padding: 7px 14px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 6px;
      transition: background 0.15s ease;
    }
    button:hover { background-color: var(--accent-hover); }
    button.btn-secondary { background-color: #444; }
    button.btn-secondary:hover { background-color: #555; }
    button.btn-outline { background-color: transparent; border: 1px solid #555; color: #ddd; }
    button.btn-outline:hover { background-color: #333; }
    button.btn-success { background-color: var(--success); }
    button.btn-success:hover { background-color: #2e7d32; }
    button.btn-sm { padding: 4px 10px; font-size: 11px; }

    .toast {
      position: fixed;
      bottom: 24px;
      right: 24px;
      background: #333;
      color: #fff;
      padding: 10px 18px;
      border-radius: 6px;
      border: 1px solid #555;
      font-size: 13px;
      display: none;
      box-shadow: 0 4px 12px rgba(0,0,0,0.5);
      z-index: 1000;
    }
  </style>
</head>
<body>
  <div class="top-header">
    <div class="header-title">
      <h1>??? SeniorDev AI Fix & Analysis Report</h1>
      <div class="header-subtitle">Scope: <strong>${this.escapeHtml(this._scopeTitle)}</strong> &bull; Total Issues: <strong>${this._issues.length}</strong></div>
    </div>
    <div class="global-actions">
      <button onclick="copyFullPrompt()">?? Copy Full AI Fix Prompt</button>
      <button class="btn-secondary" onclick="batchEnrichAll()">? Batch AI Fix All</button>
      <button class="btn-secondary" onclick="batchVerifyAll()">? Verify All Fixes</button>
      <button class="btn-outline" onclick="copyJsonReport()">?? Copy JSON</button>
    </div>
  </div>

  <div class="metrics-row">
    <div class="metric-card metric-total">
      <span class="metric-label">Total Detected Issues</span>
      <span class="metric-number">${this._issues.length}</span>
    </div>
    <div class="metric-card metric-critical">
      <span class="metric-label">Critical & High</span>
      <span class="metric-number">${criticalCount}</span>
    </div>
    <div class="metric-card metric-enriched">
      <span class="metric-label">AI Explanations Ready</span>
      <span class="metric-number">${enrichedCount} / ${this._issues.length}</span>
    </div>
    <div class="metric-card metric-verified">
      <span class="metric-label">Verified Fixed</span>
      <span class="metric-number">${verifiedCount}</span>
    </div>
  </div>

  <div class="issue-list">
    ${issueCardsHtml}
  </div>

  <div id="toast" class="toast">Prompt copied to clipboard!</div>

  <script>
    const vscode = acquireVsCodeApi();

    const issuesData = ${JSON.stringify(this._issues)};

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.innerText = msg;
      t.style.display = 'block';
      setTimeout(() => { t.style.display = 'none'; }, 3000);
    }

    function openFile(filePath, line) {
      vscode.postMessage({ command: 'openFile', filePath: filePath, line: line });
    }

    function copyFullPrompt() {
      vscode.postMessage({ command: 'copyText' });
      showToast('?? Copied Full AI Fix Prompt to Clipboard!');
    }

    function copyJsonReport() {
      const text = JSON.stringify(issuesData, null, 2);
      navigator.clipboard.writeText(text).then(() => {
        showToast('?? Copied Raw JSON Report to Clipboard!');
      });
    }

    function copySinglePrompt(issueId) {
      const issue = issuesData.find(i => i.id === issueId);
      if (!issue) return;
      const text = '# SeniorDev AI Issue Fix Request\\n' +
        '- **File:** ' + issue.filePath + ':' + issue.line + '\\n' +
        '- **Issue:** ' + issue.message + ' (' + issue.severity + ')\\n' +
        '\\n**Code Snippet:**\\n\`\`\`' + (issue.language || 'text') + '\\n' +
        (issue.snippet || '') + '\\n\`\`\`\\n' +
        '\\nPlease provide the complete, corrected code fix and explanation.';
      navigator.clipboard.writeText(text).then(() => {
        showToast('?? Copied prompt for #' + issueId.substring(0, 6) + ' to clipboard!');
      });
    }

    function batchEnrichAll() {
      vscode.postMessage({ command: 'enrichAll' });
    }

    function batchVerifyAll() {
      vscode.postMessage({ command: 'verifyAll' });
    }

    function enrichSingle(issueId) {
      vscode.postMessage({ command: 'enrichIssue', issueId: issueId });
    }

    function verifySingle(issueId) {
      vscode.postMessage({ command: 'verifyIssue', issueId: issueId });
    }

    function applySingle(issueId, filePath) {
      vscode.postMessage({ command: 'applyFix', issueId: issueId, filePath: filePath });
    }
  </script>
</body>
</html>`;
  }

  public dispose(): void {
    ProjectReportPanel.currentPanel = undefined;
    this._panel.dispose();
    while (this._disposables.length) {
      const x = this._disposables.pop();
      if (x) {
        x.dispose();
      }
    }
  }
}
