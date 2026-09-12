import * as vscode from 'vscode';
import * as path from 'path';
import { Issue, WebviewMessage } from '../types';

export class IssueDetailPanel {
  public static currentPanel: IssueDetailPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposables: vscode.Disposable[] = [];
  private _currentIssue: Issue | undefined;
  private _projectRoot: string = '';

  private constructor(
    panel: vscode.WebviewPanel,
    extensionUri: vscode.Uri,
    issue: Issue,
    projectRoot: string,
    private readonly onMessageCallback: (msg: WebviewMessage, panel: IssueDetailPanel) => void
  ) {
    this._panel = panel;
    this._extensionUri = extensionUri;
    this._currentIssue = issue;
    this._projectRoot = projectRoot;

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
    issue: Issue,
    projectRoot: string,
    onMessage: (msg: WebviewMessage, panel: IssueDetailPanel) => void
  ): IssueDetailPanel {
    const column = vscode.window.activeTextEditor
      ? vscode.ViewColumn.Beside
      : vscode.ViewColumn.One;

    if (IssueDetailPanel.currentPanel) {
      IssueDetailPanel.currentPanel._currentIssue = issue;
      IssueDetailPanel.currentPanel._projectRoot = projectRoot;
      IssueDetailPanel.currentPanel._panel.reveal(column);
      IssueDetailPanel.currentPanel.update();
      return IssueDetailPanel.currentPanel;
    }

    const panel = vscode.window.createWebviewPanel(
      'seniordevIssueDetail',
      `Issue: ${issue.message.substring(0, 30)}...`,
      column,
      {
        enableScripts: true,
        localResourceRoots: [extensionUri]
      }
    );

    IssueDetailPanel.currentPanel = new IssueDetailPanel(
      panel,
      extensionUri,
      issue,
      projectRoot,
      onMessage
    );
    return IssueDetailPanel.currentPanel;
  }

  public setIssue(issue: Issue): void {
    this._currentIssue = issue;
    this.update();
  }

  public getCurrentIssue(): Issue | undefined {
    return this._currentIssue;
  }

  public getProjectRoot(): string {
    return this._projectRoot;
  }

  private update(): void {
    if (!this._currentIssue) {
      return;
    }
    this._panel.title = `[${this._currentIssue.severity}] ${this._currentIssue.message.substring(0, 25)}...`;
    this._panel.webview.html = this._getHtmlForWebview(this._currentIssue);
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

  private _getHtmlForWebview(issue: Issue): string {
    const isEnriched = !!issue.aiExplanation;
    const hasFixCode = !!issue.aiFixCode;
    const isVerifiedFixed = issue.verificationStatus === 'VERIFIED_FIXED';
    const isVerificationFailed = issue.verificationStatus === 'VERIFICATION_FAILED';

    let statusBadgeClass = 'status-not-verified';
    let statusText = 'Not Verified';
    if (isVerifiedFixed) {
      statusBadgeClass = 'status-verified';
      statusText = 'VERIFIED FIXED';
    } else if (isVerificationFailed) {
      statusBadgeClass = 'status-failed';
      statusText = 'VERIFICATION FAILED';
    } else if (issue.verificationStatus === 'VERIFICATION_SKIPPED') {
      statusBadgeClass = 'status-skipped';
      statusText = 'VERIFICATION SKIPPED';
    }

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SeniorDev AI Issue Detail</title>
  <style>
    :root {
      --font-family: var(--vscode-font-family, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif);
      --bg: var(--vscode-editor-background);
      --fg: var(--vscode-editor-foreground);
      --card-bg: var(--vscode-sideBar-background, #1e1e1e);
      --border: var(--vscode-panel-border, #333);
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
      padding: 16px 24px;
      line-height: 1.5;
      margin: 0;
    }
    .header {
      border-bottom: 1px solid var(--border);
      padding-bottom: 14px;
      margin-bottom: 16px;
    }
    .badges {
      display: flex;
      gap: 8px;
      margin-bottom: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .badge {
      font-size: 11px;
      font-weight: 700;
      padding: 3px 8px;
      border-radius: 4px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .badge-CRITICAL, .badge-HIGH { background: #d32f2f33; color: #ff6b6b; border: 1px solid #ff6b6b; }
    .badge-MEDIUM { background: #f57c0033; color: #ffa726; border: 1px solid #ffa726; }
    .badge-LOW, .badge-INFO { background: #0288d133; color: #4fc3f7; border: 1px solid #4fc3f7; }
    .badge-lang { background: #33333388; color: #ccc; border: 1px solid #555; }
    .badge-type { background: #6200ea22; color: #b388ff; border: 1px solid #7c4dff; }

    .title {
      font-size: 18px;
      font-weight: 600;
      margin: 4px 0 8px 0;
    }
    .file-link {
      font-size: 13px;
      color: var(--vscode-textLink-foreground, #3794ff);
      cursor: pointer;
      text-decoration: underline;
      display: inline-block;
      margin-bottom: 12px;
    }
    .file-link:hover {
      color: var(--vscode-textLink-activeForeground, #70aeff);
    }
    .card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 16px;
      margin-bottom: 16px;
    }
    .card-title {
      font-size: 14px;
      font-weight: 600;
      margin-top: 0;
      margin-bottom: 10px;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .explanation-text {
      white-space: pre-wrap;
      font-size: 13px;
      line-height: 1.6;
    }
    .diff-container {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin-top: 10px;
    }
    @media (max-width: 700px) {
      .diff-container {
        grid-template-columns: 1fr;
      }
    }
    .diff-box {
      border: 1px solid var(--border);
      border-radius: 4px;
      overflow: hidden;
    }
    .diff-box-header {
      padding: 6px 10px;
      font-size: 12px;
      font-weight: bold;
    }
    .diff-box-original .diff-box-header {
      background: #d32f2f22;
      color: #ff8a80;
      border-bottom: 1px solid #d32f2f44;
    }
    .diff-box-fix .diff-box-header {
      background: #388e3c22;
      color: #b9f6ca;
      border-bottom: 1px solid #388e3c44;
    }
    pre {
      margin: 0;
      padding: 12px;
      font-family: var(--vscode-editor-font-family, Consolas, 'Courier New', monospace);
      font-size: 12px;
      overflow-x: auto;
      background: #00000033;
    }
    .verification-card {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .status-badge {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 4px;
      font-weight: bold;
      font-size: 12px;
      width: fit-content;
    }
    .status-verified { background: #388e3c; color: #fff; }
    .status-failed { background: #d32f2f; color: #fff; }
    .status-not-verified { background: #555; color: #eee; }
    .status-skipped { background: #666; color: #ddd; }

    .verification-detail {
      font-size: 12px;
      background: #00000044;
      padding: 8px 12px;
      border-radius: 4px;
      border-left: 3px solid var(--border);
      white-space: pre-wrap;
      font-family: monospace;
    }
    .actions-bar {
      display: flex;
      gap: 12px;
      margin-top: 16px;
      flex-wrap: wrap;
    }
    button {
      background-color: var(--accent);
      color: #ffffff;
      border: none;
      padding: 8px 16px;
      border-radius: 4px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 6px;
      transition: background 0.15s ease;
    }
    button:hover {
      background-color: var(--accent-hover);
    }
    button.btn-secondary {
      background-color: #444;
    }
    button.btn-secondary:hover {
      background-color: #555;
    }
    button.btn-success {
      background-color: var(--success);
    }
    button.btn-success:hover {
      background-color: #2e7d32;
    }
  </style>
</head>
<body>
  <div class="header">
    <div class="badges">
      <span class="badge badge-${this.escapeHtml(issue.severity)}">${this.escapeHtml(issue.severity)}</span>
      <span class="badge badge-lang">${this.escapeHtml(issue.language)}</span>
      <span class="badge badge-type">${this.escapeHtml(issue.type)}</span>
    </div>
    <div class="title">${this.escapeHtml(issue.message)}</div>
    <div class="file-link" onclick="openTargetFile()">
      ?? ${this.escapeHtml(issue.filePath)} : Line ${issue.line}
    </div>
  </div>

  ${isEnriched ? `
  <div class="card">
    <div class="card-title">?? Senior Dev Explanation</div>
    <div class="explanation-text">${this.escapeHtml(issue.aiExplanation)}</div>
  </div>

  <div class="card">
    <div class="card-title">?? Code Comparison & Fix Proposal</div>
    <div class="diff-container">
      <div class="diff-box diff-box-original">
        <div class="diff-box-header">Original Snippet (Line ${issue.line})</div>
        <pre><code>${this.escapeHtml(issue.snippet || '// No snippet available')}</code></pre>
      </div>
      <div class="diff-box diff-box-fix">
        <div class="diff-box-header">AI Proposed Fix</div>
        <pre><code>${this.escapeHtml(issue.aiFixCode || '// No fix generated')}</code></pre>
      </div>
    </div>
  </div>

  <div class="card verification-card">
    <div class="card-title">??? Automated Sandbox Verification</div>
    <div>
      <span class="status-badge ${statusBadgeClass}">${statusText}</span>
    </div>
    ${issue.verificationDetail ? `
      <div class="verification-detail">${this.escapeHtml(issue.verificationDetail)}</div>
    ` : ''}
  </div>

  <div class="actions-bar">
    <button class="btn-secondary" onclick="verifyFix()">
      ? Verify Fix in Sandbox
    </button>
    <button class="${isVerifiedFixed ? 'btn-success' : ''}" onclick="applyFix()">
      ${isVerifiedFixed ? '? Apply Verified Fix to File' : '?? Apply Fix to File'}
    </button>
  </div>
  ` : `
  <div class="card" style="text-align: center; padding: 32px 16px;">
    <div style="font-size: 32px; margin-bottom: 12px;">??</div>
    <div style="font-size: 15px; font-weight: 600; margin-bottom: 8px;">AI Guidance Not Generated Yet</div>
    <div style="font-size: 13px; color: #888; max-width: 400px; margin: 0 auto 16px auto;">
      Request senior developer insights and a verified code solution directly from your local Ollama instance.
    </div>
    <div style="display: flex; justify-content: center;">
      <button onclick="enrichNow()">? Get AI Explanation & Fix</button>
    </div>
  </div>

  ${issue.snippet ? `
  <div class="card">
    <div class="card-title">Current Code Snippet (Line ${issue.line})</div>
    <pre><code>${this.escapeHtml(issue.snippet)}</code></pre>
  </div>
  ` : ''}
  `}

  <script>
    const vscode = acquireVsCodeApi();

    function openTargetFile() {
      vscode.postMessage({
        command: 'openFile',
        issueId: '${issue.id}',
        filePath: '${this.escapeHtml(issue.filePath)}',
        line: ${issue.line}
      });
    }

    function enrichNow() {
      vscode.postMessage({
        command: 'enrichIssue',
        issueId: '${issue.id}'
      });
    }

    function verifyFix() {
      vscode.postMessage({
        command: 'verifyIssue',
        issueId: '${issue.id}'
      });
    }

    function applyFix() {
      vscode.postMessage({
        command: 'applyFix',
        issueId: '${issue.id}',
        filePath: '${this.escapeHtml(issue.filePath)}'
      });
    }
  </script>
</body>
</html>`;
  }

  public dispose(): void {
    IssueDetailPanel.currentPanel = undefined;
    this._panel.dispose();
    while (this._disposables.length) {
      const x = this._disposables.pop();
      if (x) {
        x.dispose();
      }
    }
  }
}
