import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { BackendClient } from '../client/backendClient';
import { Issue } from '../types';

interface IssueCard {
  issue: Issue;
  status: 'idle' | 'loading' | 'applied' | 'error';
  summary: string;
  errorText: string;
  aiExplanation: string;
  aiFixCode: string;
}

interface HistoryEntry {
  timestamp: string;
  action: 'diagnose' | 'fix';
  fileName: string;
  filePath: string;
  issuesFound: number;
  explanation: string;
}

interface ViewState {
  page: 'idle' | 'scanning' | 'file_results' | 'project_results' | 'error';
  scanProgress: string;
  activeFileName: string;
  activeFilePath: string;
  language: string;
  sessionId: string;
  cards: IssueCard[];
  unifiedFix: {
    applied: boolean;
    accepted: boolean;
    explanation: string;
    originalContent: string;
    fixedContent: string;
    issuesCount: number;
    errorsFound: { line: number; message: string }[];
  } | null;
  projectSummary: {
    totalIssues: number;
    files: { filePath: string; fileName: string; issueCount: number; issues: Issue[] }[];
    buildSystem: string;
  } | null;
  errorText: string;
  history: HistoryEntry[];
}

export class ChatViewProvider implements vscode.WebviewViewProvider {
  public static readonly viewType = 'seniordev.chatView';
  private _view?: vscode.WebviewView;
  private _activeFilePath = '';
  private _fileBackups: Map<string, string> = new Map(); // filePath -> original file content
  private _historyFilePath: string = '';
  private _state: ViewState = {
    page: 'idle', scanProgress: '', activeFileName: '', activeFilePath: '',
    language: '', sessionId: '', cards: [], unifiedFix: null, projectSummary: null, errorText: '', history: []
  };

  private _addedDecorationType = vscode.window.createTextEditorDecorationType({
    backgroundColor: 'rgba(46, 125, 50, 0.28)',
    isWholeLine: true,
    overviewRulerColor: '#43a047',
    overviewRulerLane: vscode.OverviewRulerLane.Left
  });

  private _removedDecorationType = vscode.window.createTextEditorDecorationType({
    overviewRulerColor: '#e57373',
    overviewRulerLane: vscode.OverviewRulerLane.Right
  });

  constructor(
    private readonly _extensionUri: vscode.Uri,
    private readonly _backendClient: BackendClient
  ) {
    this._historyFilePath = path.join(_extensionUri.fsPath, '.seniordev-history.json');
    this._loadHistory();
    this._captureActive();
    vscode.window.onDidChangeActiveTextEditor(e => {
      if (e && !e.document.isUntitled) this._setActive(e.document.uri.fsPath);
    });
    vscode.window.onDidChangeVisibleTextEditors(eds => {
      const v = eds.find(e => !e.document.isUntitled);
      if (v) this._setActive(v.document.uri.fsPath);
    });
  }

  private _loadHistory(): void {
    try {
      if (fs.existsSync(this._historyFilePath)) {
        const data = fs.readFileSync(this._historyFilePath, 'utf-8');
        this._state.history = JSON.parse(data);
      }
    } catch { this._state.history = []; }
  }

  private _saveHistory(): void {
    try {
      if (this._state.history.length > 50) {
        this._state.history = this._state.history.slice(-50);
      }
      fs.writeFileSync(this._historyFilePath, JSON.stringify(this._state.history, null, 2), 'utf-8');
    } catch { /* ignore write errors */ }
  }

  private _addHistory(action: 'diagnose' | 'fix', fileName: string, filePath: string, issuesFound: number, explanation: string): void {
    const entry: HistoryEntry = { timestamp: new Date().toISOString(), action, fileName, filePath, issuesFound, explanation };
    this._state.history.push(entry);
    this._saveHistory();
  }

  private _captureActive(): void {
    const ed = vscode.window.activeTextEditor;
    if (ed && !ed.document.isUntitled) { this._activeFilePath = ed.document.uri.fsPath; return; }
    const v = vscode.window.visibleTextEditors.find(e => !e.document.isUntitled);
    if (v) this._activeFilePath = v.document.uri.fsPath;
  }

  private _resolveLanguage(fp: string): string {
    if (!fp) return 'python';
    const ext = path.extname(fp).toLowerCase();
    switch (ext) {
      case '.py':
      case '.pyw':
        return 'python';
      case '.java':
        return 'java';
      case '.js':
      case '.mjs':
      case '.cjs':
        return 'javascript';
      case '.ts':
      case '.tsx':
        return 'typescript';
      case '.cpp':
      case '.cc':
      case '.cxx':
      case '.hpp':
      case '.h':
        return 'cpp';
      case '.c':
        return 'c';
      case '.go':
        return 'go';
      case '.rs':
        return 'rust';
      case '.rb':
        return 'ruby';
      case '.php':
        return 'php';
      case '.cs':
        return 'csharp';
      case '.html':
        return 'html';
      case '.css':
        return 'css';
      case '.json':
        return 'json';
      default:
        return ext.replace('.', '') || 'python';
    }
  }

  private _setActive(fp: string): void {
    if (!fp) return;
    const isNew = this._activeFilePath !== fp;
    this._activeFilePath = fp;
    this._state.activeFileName = path.basename(fp);
    this._state.activeFilePath = fp;
    this._state.language = this._resolveLanguage(fp);
    if (isNew) {
      this._state.unifiedFix = null;
      this._state.cards = [];
      this._state.page = 'idle';
    }
    this._push();
  }

  private _getFile(): string | undefined {
    if (vscode.window.activeTextEditor && !vscode.window.activeTextEditor.document.isUntitled)
      return vscode.window.activeTextEditor.document.uri.fsPath;
    const v = vscode.window.visibleTextEditors.find(e => !e.document.isUntitled);
    if (v) return v.document.uri.fsPath;
    if (this._activeFilePath && fs.existsSync(this._activeFilePath)) return this._activeFilePath;
    return undefined;
  }

  private _wsRoot(): string {
    const f = vscode.workspace.workspaceFolders;
    if (f && f.length > 0) return f[0].uri.fsPath;
    if (this._activeFilePath) return path.dirname(this._activeFilePath);
    return process.cwd();
  }

  public resolveWebviewView(wv: vscode.WebviewView, _ctx: vscode.WebviewViewResolveContext, _tok: vscode.CancellationToken) {
    this._view = wv;
    wv.webview.options = { enableScripts: true, localResourceRoots: [this._extensionUri] };
    this._captureActive();
    if (this._activeFilePath) {
      this._state.activeFileName = path.basename(this._activeFilePath);
      this._state.activeFilePath = this._activeFilePath;
      this._state.language = this._resolveLanguage(this._activeFilePath);
    }
    wv.webview.html = this._html();
    wv.webview.onDidReceiveMessage(async (msg) => {
      try {
        switch (msg.action) {
          case 'ready': this._captureActive(); if (this._activeFilePath) this._setActive(this._activeFilePath); this._push(); break;
          case 'diagnose': await this.diagnoseFile(); break;
          case 'fixAI': await this.fixFileAI(); break;
          case 'pick': await this._pickFile(); break;
          case 'fixOne': await this._fixOne(msg.index); break;
          case 'undoOne': await this._undoOne(msg.index); break;
          case 'copyCode': await this._copyCode(msg.index); break;
          case 'showDiff': await this._showDiff(msg.index); break;
          case 'acceptFix': await this._acceptFix(); break;
          case 'undoFix': await this._undoFix(); break;
          case 'showUnifiedDiff': await this._showUnifiedDiff(); break;
          case 'clearHistory': this._state.history = []; this._saveHistory(); this._push(); break;
          case 'openFile': if (msg.filePath) await this._openFile(msg.filePath); break;
          case 'fixFile': if (msg.filePath) await this._fixSpecificFile(msg.filePath); break;
        }
      } catch (err: any) {
        vscode.window.showErrorMessage('SeniorDev AI: ' + err.message);
      }
    });
  }

  private _push(): void {
    if (this._view) this._view.webview.postMessage({ type: 'state', state: this._state });
  }

  public getSessionId(): string { return this._state.sessionId; }
  public async fixActiveFile(): Promise<void> { await this.fixFileAI(); }

  private async _pickFile(): Promise<void> {
    const files = await vscode.workspace.findFiles('**/*', '**/node_modules/**,**/target/**,**/dist/**,**/.git/**');
    if (!files.length) { vscode.window.showInformationMessage('No files in workspace.'); return; }
    const items = files.map(f => ({ label: path.basename(f.fsPath), description: vscode.workspace.asRelativePath(f), fsPath: f.fsPath }));
    const sel = await vscode.window.showQuickPick(items, { placeHolder: 'Select a file to analyze' });
    if (sel) { const d = await vscode.workspace.openTextDocument(vscode.Uri.file(sel.fsPath)); await vscode.window.showTextDocument(d); this._setActive(sel.fsPath); }
  }

  private async _openFile(fp: string): Promise<void> {
    const d = await vscode.workspace.openTextDocument(vscode.Uri.file(fp));
    await vscode.window.showTextDocument(d); this._setActive(fp);
  }

  private async _fixSpecificFile(fp: string): Promise<void> {
    const d = await vscode.workspace.openTextDocument(vscode.Uri.file(fp));
    await vscode.window.showTextDocument(d); this._setActive(fp);
    await this.fixFileAI();
  }

  /* -- Diagnose (fast, no AI) -- */
  public async diagnoseFile(): Promise<void> {
    const fp = this._getFile();
    if (!fp) { await this._pickFile(); return; }
    this._setActive(fp);
    this._state.page = 'scanning';
    this._state.scanProgress = 'Running compiler & linter checks\u2026';
    this._state.cards = [];
    this._state.unifiedFix = null;
    this._state.projectSummary = null;
    this._push();
    try {
      const a = await this._backendClient.analyzeProject(this._wsRoot(), fp);
      this._state.sessionId = a.sessionId;
      this._state.cards = (a.issues || []).map(i => ({
        issue: i, status: 'idle' as const, summary: '', errorText: '',
        aiExplanation: '', aiFixCode: ''
      }));
      this._state.unifiedFix = null;
      this._state.page = 'file_results';
      this._state.scanProgress = '';
      this._addHistory('diagnose', this._state.activeFileName, fp, (a.issues || []).length,
        (a.issues || []).length > 0 ? (a.issues || []).length + ' issue(s) found' : 'No issues found');
      this._push();
    } catch (err: any) {
      this._state.page = 'error'; this._state.errorText = err.message; this._push();
    }
  }

  /* -- Fix File (AI) - Unified Whole-File Fix -- */
  public async fixFileAI(): Promise<void> {
    const fp = this._getFile();
    if (!fp) { await this._pickFile(); return; }
    this._setActive(fp);
    this._state.page = 'scanning';
    this._state.scanProgress = 'Running compiler & linter checks\u2026';
    this._state.cards = []; this._state.unifiedFix = null; this._state.projectSummary = null;
    this._push();
    try {
      const a = await this._backendClient.analyzeProject(this._wsRoot(), fp);
      this._state.sessionId = a.sessionId;
      this._state.cards = (a.issues || []).map(i => ({
        issue: i, status: 'idle' as const, summary: '', errorText: '',
        aiExplanation: '', aiFixCode: ''
      }));

      this._state.scanProgress = 'AI is analyzing file for all errors\u2026';
      this._push();

      // Read current document content
      const fileUri = vscode.Uri.file(fp);
      const doc = await vscode.workspace.openTextDocument(fileUri);
      const currentContent = doc.getText();
      this._fileBackups.set(fp, currentContent);

      // Call whole-file AI fix endpoint
      const fixRes = await this._backendClient.fixWholeFile({
        sessionId: a.sessionId,
        filePath: fp,
        fileContent: currentContent,
        language: this._state.language,
        issues: a.issues
      });

      let fixedContent = fixRes.fixedContent;
      const explanation = fixRes.explanation;

      if (!fixedContent) {
        throw new Error(explanation || 'AI failed to generate fix code.');
      }

      const isExplanation = (text: string): boolean => {
        const lower = text.trim().toLowerCase();
        const indicators = [
          'the code is',
          'this code is',
          'already free of',
          'free of syntax errors',
          'no issues found',
          'no errors found',
          'no changes needed',
          'no changes are needed',
          'code is correct',
          'already correct',
          'not applicable in java',
          'not applicable',
          'does not need',
          'there are no syntax',
          'there are no errors',
          'the provided code'
        ];
        return indicators.some(ind => lower.includes(ind));
      };

      const isRealCode = (text: string, lang: string): boolean => {
        const trimmed = text.trim();
        if (trimmed.length < 10) return false;
        if (isExplanation(trimmed)) return false;

        const l = (lang || '').toLowerCase();
        if (l === 'java' || l === 'typescript' || l === 'javascript' || l === 'c' || l === 'cpp') {
          const hasBraces = trimmed.includes('{') && trimmed.includes('}');
          const hasSemicolon = trimmed.includes(';');
          const hasKeywords = /\b(class|interface|record|enum|package|public|import|function|const|let|var)\b/.test(trimmed);
          if (!hasBraces && !hasSemicolon && !hasKeywords) {
            return false;
          }
        } else if (l === 'python' || l === 'py') {
          const hasPy = /\b(def|class|import|from|for|while|if|return|print)\b/.test(trimmed) || trimmed.includes('=');
          if (!hasPy) {
            return false;
          }
        }
        return true;
      };

      // Guard against cross-language contamination
      const isContaminated = (code: string, lang: string): boolean => {
        const l = (lang || '').toLowerCase();
        if (l === 'python' || l === 'py') {
          if (code.includes('public class ') || code.includes('public static void main') ||
              code.includes('import java.') || code.includes('System.out.') ||
              code.includes('Scanner scanner') || (/;\s*(\r?\n|$)/.test(code) && code.includes('{'))) {
            return true;
          }
        } else if (l === 'java') {
          if (/\bdef\s+\w+\s*\(/.test(code) || /\belif\b/.test(code) || code.includes('import numpy')) {
            return true;
          }
        }
        return false;
      };

      if (isContaminated(fixedContent, this._state.language)) {
        vscode.window.showErrorMessage(`SeniorDev AI: AI generated code in the wrong language for ${this._state.activeFileName}. Fix rejected to protect your file.`);
        this._state.scanProgress = '';
        this._push();
        return;
      }

      // If AI says no changes needed, or fixedContent is an explanation instead of real code, skip file edit
      const isNoChange = fixedContent.trim() === currentContent.trim() ||
        !isRealCode(fixedContent, this._state.language);

      if (isNoChange) {
        this._state.unifiedFix = {
          applied: false,
          accepted: true,
          explanation: explanation || 'No issues found — code is correct.',
          originalContent: currentContent,
          fixedContent: currentContent,
          issuesCount: 0,
          errorsFound: []
        };
        this._state.page = 'file_results';
        this._state.scanProgress = '';
        this._addHistory('fix', this._state.activeFileName, fp, 0, 'No issues found — code is correct.');
        this._push();
        vscode.window.showInformationMessage('SeniorDev AI: Code is already correct! No changes needed.');
        return;
      }

      // Apply whole-file replacement cleanly in 1 operation
      const fullRange = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      const edit = new vscode.WorkspaceEdit();
      edit.replace(fileUri, fullRange, fixedContent);
      const ok = await vscode.workspace.applyEdit(edit);
      if (ok) {
        await doc.save();
        const editor = await vscode.window.showTextDocument(doc, { preview: false, preserveFocus: false });
        this._applyDecorations(editor, currentContent, fixedContent);

        this._state.unifiedFix = {
          applied: true,
          accepted: false,
          explanation: explanation || 'AI fixed all detected issues in this file.',
          originalContent: currentContent,
          fixedContent: fixedContent,
          issuesCount: a.issues.length,
          errorsFound: fixRes.errorsFound || []
        };
        this._state.page = 'file_results';
        this._state.scanProgress = '';
        this._addHistory('fix', this._state.activeFileName, fp, a.issues.length,
          explanation || 'AI fixed ' + a.issues.length + ' issue(s)');
        this._push();
      }
    } catch (err: any) {
      this._state.page = 'error'; this._state.errorText = err.message; this._push();
    }
  }

  private async _acceptFix(): Promise<void> {
    const fp = this._state.activeFilePath;
    if (fp) {
      this._fileBackups.delete(fp);
    }
    this._clearDecorations();
    this._state.unifiedFix = null;
    vscode.window.showInformationMessage('SeniorDev AI Fix accepted!');
    await this.diagnoseFile();
  }

  private async _undoFix(): Promise<void> {
    const fp = this._state.activeFilePath;
    if (!fp) return;
    const backup = (this._state.unifiedFix && this._state.unifiedFix.originalContent) || this._fileBackups.get(fp);
    if (backup === undefined) {
      vscode.window.showErrorMessage('No backup found to undo fix.');
      return;
    }
    try {
      const fileUri = vscode.Uri.file(fp);
      const doc = await vscode.workspace.openTextDocument(fileUri);
      const fullRange = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      const edit = new vscode.WorkspaceEdit();
      edit.replace(fileUri, fullRange, backup);
      const ok = await vscode.workspace.applyEdit(edit);
      if (ok) {
        await doc.save();
        const editor = await vscode.window.showTextDocument(doc, { preview: false });
        this._clearDecorations(editor);
        this._state.unifiedFix = null;
        this._fileBackups.delete(fp);
        vscode.window.showInformationMessage('Reverted AI fix for this file.');
        await this.diagnoseFile();
      }
    } catch (err: any) {
      vscode.window.showErrorMessage('Undo failed: ' + err.message);
    }
    this._push();
  }

  private async _showUnifiedDiff(): Promise<void> {
    const fp = this._state.activeFilePath;
    if (!fp || !this._state.unifiedFix) return;
    const originalContent = this._state.unifiedFix.originalContent || this._fileBackups.get(fp) || '';

    const fileName = path.basename(fp);
    const originalUri = vscode.Uri.parse(`seniordev-diff-orig:${fileName}`);
    const currentUri = vscode.Uri.file(fp);

    const originalProvider = new (class implements vscode.TextDocumentContentProvider {
      provideTextDocumentContent(): string {
        return originalContent;
      }
    })();

    const providerRegistration = vscode.workspace.registerTextDocumentContentProvider('seniordev-diff-orig', originalProvider);

    await vscode.commands.executeCommand(
      'vscode.diff',
      originalUri,
      currentUri,
      `${fileName}: Original ↔ SeniorDev AI Fix`,
      { preview: true }
    );

    setTimeout(() => providerRegistration.dispose(), 60000);
  }

  private async _fixOne(idx: number): Promise<void> {
    await this._applyFixForCard(idx);
  }

  private async _undoOne(idx: number): Promise<void> {
    const fp = this._state.activeFilePath;
    if (!fp) return;
    await this._rollbackFile(fp);
  }

  private async _applyFixForCard(idx: number): Promise<void> {
    const card = this._state.cards[idx];
    if (!card) return;

    const fp = this._state.activeFilePath;
    if (!fp || !fs.existsSync(fp)) {
      card.status = 'error';
      card.errorText = 'No active file found to fix.';
      this._push();
      return;
    }

    card.status = 'loading';
    this._push();

    try {
      // 1. Call AI model if fix code not already populated
      if (!card.aiFixCode) {
        const enriched = await this._backendClient.enrichIssues(this._state.sessionId, [card.issue.id]);
        if (enriched.length === 0) {
          throw new Error('AI failed to generate a fix.');
        }
        card.aiFixCode = enriched[0].aiFixCode || '';
        card.aiExplanation = enriched[0].aiExplanation || '';
      }

      const aiFixCode = card.aiFixCode;
      const aiExplanation = card.aiExplanation;

      if (!aiFixCode) {
        throw new Error('AI generated an empty fix.');
      }

      // 2. Read current content from open text document
      const fileUri = vscode.Uri.file(fp);
      const doc = await vscode.workspace.openTextDocument(fileUri);
      const currentContent = doc.getText();
      if (!this._fileBackups.has(fp)) {
        this._fileBackups.set(fp, currentContent);
      }

      // 3. Clean non-duplicating code replacement strategy
      const snippet = (card.issue.snippet || '').trim();
      let newText = '';

      const normCurrent = currentContent.replace(/\r\n/g, '\n');
      const normSnippet = snippet.replace(/\r\n/g, '\n');
      const normFixCode = aiFixCode.replace(/\r\n/g, '\n').trim();

      if (normSnippet && normCurrent.includes(normSnippet)) {
        newText = normCurrent.replace(normSnippet, normFixCode);
      } else if (normSnippet.trim() && normCurrent.includes(normSnippet.trim())) {
        newText = normCurrent.replace(normSnippet.trim(), normFixCode);
      } else {
        const lines = normCurrent.split('\n');
        const lineIdx = Math.max(0, card.issue.line - 1);
        if (lineIdx < lines.length) {
          const snippetLineCount = normSnippet ? normSnippet.split('\n').length : 1;
          const endIdx = Math.min(lines.length, lineIdx + snippetLineCount);
          const before = lines.slice(0, lineIdx);
          const after = lines.slice(endIdx);
          newText = [...before, normFixCode, ...after].join('\n');
        } else {
          newText = normFixCode;
        }
      }

      // 4. Apply workspace edit directly to active editor and save
      const fullRange = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      const edit = new vscode.WorkspaceEdit();
      edit.replace(fileUri, fullRange, newText);
      const ok = await vscode.workspace.applyEdit(edit);
      if (ok) {
        await doc.save();
        card.status = 'applied';
        card.summary = aiExplanation;
        const editor = await vscode.window.showTextDocument(doc, { preview: false, preserveFocus: false });
        this._applyDecorations(editor, currentContent, newText);
      } else {
        throw new Error('Failed to apply edits to the active document.');
      }
    } catch (err: any) {
      card.status = 'error';
      card.errorText = err.message;
    }
    this._push();
  }

  private async _showDiff(idx: number): Promise<void> {
    const card = this._state.cards[idx];
    if (!card || !card.issue) return;
    await vscode.commands.executeCommand('seniordev.showInlineDiff', card.issue);
  }

  private _applyDecorations(editor: vscode.TextEditor, oldText: string, newText: string): void {
    const oldLines = oldText.split(/\r?\n/);
    const newLines = newText.split(/\r?\n/);

    const addedRanges: vscode.Range[] = [];
    const removedDecorations: vscode.DecorationOptions[] = [];

    // LCS line-level diff
    const m = oldLines.length;
    const n = newLines.length;
    const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));

    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (oldLines[i - 1] === newLines[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }

    let i = m;
    let j = n;
    interface DiffOp {
      type: 'common' | 'added' | 'removed';
      oldLine?: string;
      newLine?: string;
      newIndex?: number;
    }
    const ops: DiffOp[] = [];

    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
        ops.unshift({ type: 'common', newLine: newLines[j - 1], newIndex: j - 1 });
        i--;
        j--;
      } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
        ops.unshift({ type: 'added', newLine: newLines[j - 1], newIndex: j - 1 });
        j--;
      } else if (i > 0 && (j === 0 || dp[i][j - 1] < dp[i - 1][j])) {
        ops.unshift({ type: 'removed', oldLine: oldLines[i - 1] });
        i--;
      }
    }

    let lastCommonNewIndex = 0;
    let idx = 0;
    while (idx < ops.length) {
      if (ops[idx].type === 'common') {
        lastCommonNewIndex = ops[idx].newIndex ?? lastCommonNewIndex;
        idx++;
      } else {
        const blockRemoved: string[] = [];
        const blockAdded: { text: string; newIndex: number }[] = [];

        while (idx < ops.length && ops[idx].type !== 'common') {
          if (ops[idx].type === 'removed') {
            const t = ops[idx].oldLine ?? '';
            if (t.trim().length > 0) {
              blockRemoved.push(t.trim());
            }
          } else if (ops[idx].type === 'added') {
            blockAdded.push({ text: ops[idx].newLine ?? '', newIndex: ops[idx].newIndex! });
          }
          idx++;
        }

        // Apply green highlight to all added lines in this hunk
        for (const a of blockAdded) {
          addedRanges.push(new vscode.Range(a.newIndex, 0, a.newIndex, a.text.length));
        }

        // Attach removed code directly to the changed line
        if (blockRemoved.length > 0) {
          if (blockAdded.length > 0) {
            // Case 1: Replaced lines -> attach directly to the corresponding added line
            const remMap = new Map<number, string[]>();
            for (let r = 0; r < blockRemoved.length; r++) {
              const targetAdded = blockAdded[Math.min(r, blockAdded.length - 1)];
              const lineIdx = targetAdded.newIndex;
              if (!remMap.has(lineIdx)) {
                remMap.set(lineIdx, []);
              }
              remMap.get(lineIdx)!.push(blockRemoved[r]);
            }

            for (const [lineIdx, rems] of remMap.entries()) {
              const lineLen = (newLines[lineIdx] || '').length;
              removedDecorations.push({
                range: new vscode.Range(lineIdx, lineLen, lineIdx, lineLen),
                renderOptions: {
                  after: {
                    contentText: '  🔴 - ' + rems.join('; '),
                    color: '#ff8a80',
                    backgroundColor: 'rgba(198, 40, 40, 0.25)',
                    margin: '0 0 0 16px',
                    fontStyle: 'italic',
                    border: '1px solid rgba(244, 67, 54, 0.4)'
                  }
                }
              });
            }
          } else {
            // Case 2: Pure deletion (no added lines) -> attach to previous line
            const targetLine = Math.min(lastCommonNewIndex, Math.max(0, newLines.length - 1));
            const lineLen = (newLines[targetLine] || '').length;
            removedDecorations.push({
              range: new vscode.Range(targetLine, lineLen, targetLine, lineLen),
              renderOptions: {
                after: {
                  contentText: '  🔴 - ' + blockRemoved.join('; '),
                  color: '#ff8a80',
                  backgroundColor: 'rgba(198, 40, 40, 0.25)',
                  margin: '0 0 0 16px',
                  fontStyle: 'italic',
                  border: '1px solid rgba(244, 67, 54, 0.4)'
                }
              }
            });
          }
        }
      }
    }

    editor.setDecorations(this._addedDecorationType, addedRanges);
    editor.setDecorations(this._removedDecorationType, removedDecorations);
  }

  private _clearDecorations(editor?: vscode.TextEditor): void {
    const ed = editor || vscode.window.activeTextEditor;
    if (ed) {
      ed.setDecorations(this._addedDecorationType, []);
      ed.setDecorations(this._removedDecorationType, []);
    }
  }

  private async _rollbackFile(fp: string): Promise<void> {
    const backup = this._fileBackups.get(fp);
    if (!backup) {
      vscode.window.showErrorMessage('No backups found to revert this file.');
      return;
    }
    try {
      const fileUri = vscode.Uri.file(fp);
      const doc = await vscode.workspace.openTextDocument(fileUri);
      const fullRange = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      const edit = new vscode.WorkspaceEdit();
      edit.replace(fileUri, fullRange, backup);
      const ok = await vscode.workspace.applyEdit(edit);
      if (ok) {
        await doc.save();
        const editor = await vscode.window.showTextDocument(doc, { preview: false });
        this._clearDecorations(editor);
        for (const card of this._state.cards) {
          card.status = 'idle';
          card.summary = '';
          card.errorText = '';
        }
        this._fileBackups.delete(fp);
        vscode.window.showInformationMessage('Reverted all active fixes for this file.');
      }
    } catch (err: any) {
      vscode.window.showErrorMessage('Rollback failed: ' + err.message);
    }
    this._push();
  }

  private async _copyCode(idx: number): Promise<void> {
    const card = this._state.cards[idx]; if (!card || !card.aiFixCode) return;
    await vscode.env.clipboard.writeText(card.aiFixCode);
    vscode.window.showInformationMessage('Copied fix code to clipboard.');
  }

  private async _copyPromptImpl(): Promise<void> {
    const fp = this._getFile();
    if (!fp || !fs.existsSync(fp)) { await this._pickFile(); return; }
    const fileName = path.basename(fp);
    const fullText = fs.readFileSync(fp, 'utf-8');
    const lang = path.extname(fp).replace('.', '') || 'java';
    this._state.page = 'scanning'; this._state.scanProgress = 'Generating prompt\u2026'; this._push();
    try {
      const a = await this._backendClient.analyzeProject(this._wsRoot(), fp);
      const issues = a.issues || [];
      const list = issues.length > 0
        ? issues.map((i, x) => (x+1) + '. [' + i.severity + '] Line ' + i.line + ': ' + i.message).join('\n')
        : 'No issues.';
      const prompt = '# Fix: ' + fileName + '\n\n## Issues\n' + list + '\n\n## Code (' + lang + ')\n```' + lang + '\n' + fullText + '\n```\n\nProvide corrected whole-file replacement.';
      await vscode.env.clipboard.writeText(prompt);
      vscode.window.showInformationMessage('Copied AI fix prompt to clipboard!');
      this._state.sessionId = a.sessionId;
      this._state.cards = issues.map(i => ({
        issue: i, status: 'idle' as const, summary: '', errorText: '',
        aiExplanation: '', aiFixCode: ''
      }));
      this._state.page = 'file_results'; this._state.scanProgress = ''; this._push();
    } catch (err: any) {
      this._state.page = 'error'; this._state.errorText = err.message; this._push();
    }
  }

  public async scanProject(): Promise<void> {
    this._state.page = 'scanning'; this._state.scanProgress = 'Scanning entire project\u2026';
    this._state.cards = []; this._state.projectSummary = null; this._push();
    try {
      const a = await this._backendClient.analyzeProject(this._wsRoot());
      const issues = a.issues || [];
      const fm = new Map<string, Issue[]>();
      for (const i of issues) { const l = fm.get(i.filePath) || []; l.push(i); fm.set(i.filePath, l); }
      const files = [];
      for (const [fp, fi] of fm.entries()) files.push({ filePath: fp, fileName: path.basename(fp), issueCount: fi.length, issues: fi });
      this._state.projectSummary = { totalIssues: issues.length, files, buildSystem: a.buildSystem };
      this._state.page = 'project_results'; this._state.scanProgress = ''; this._push();
    } catch (err: any) {
      this._state.page = 'error'; this._state.errorText = err.message; this._push();
    }
  }

  /* ================================================================ */
  /*  HTML - uses data-action attributes, NOT inline onclick strings  */
  /*  IMPORTANT: No template literals (backticks) allowed here -      */
  /*  esbuild embeds them raw, breaking webview JS parsing.           */
  /* ================================================================ */

  private _html(): string {
    var cssLines = [
      ':root {',
      '  --bg: var(--vscode-sideBar-background, #181818);',
      '  --ed: var(--vscode-editor-background, #1e1e1e);',
      '  --fg: var(--vscode-editor-foreground, #ccc);',
      '  --bdr: var(--vscode-panel-border, #2d2d2d);',
      '  --ac: var(--vscode-button-background, #0e639c);',
      '  --ach: var(--vscode-button-hoverBackground, #1177bb);',
      '  --ok: #388e3c; --warn: #ef6c00; --err: #c62828;',
      '}',
      '* { box-sizing: border-box; margin: 0; padding: 0; }',
      'body { font-family: var(--vscode-font-family, -apple-system, BlinkMacSystemFont, sans-serif); background: var(--bg); color: var(--fg); padding: 10px; overflow-y: auto; height: 100vh; }',
      '.top-bar { background: var(--ed); border: 1px solid var(--bdr); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }',
      '.file-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }',
      '.file-name { font-size: 12px; font-weight: 600; color: #9cdcfe; max-width: 72%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }',
      '.btn-sm { background: #2d2d2d; color: #ccc; border: 1px solid #444; border-radius: 4px; padding: 3px 8px; font-size: 11px; cursor: pointer; }',
      '.btn-sm:hover { background: #3d3d3d; color: #fff; }',
      '.action-row { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }',
      '.btn { border: none; border-radius: 6px; padding: 8px 10px; font-size: 11.5px; font-weight: 600; cursor: pointer; text-align: center; transition: background .15s; }',
      '.btn-accent { background: var(--ac); color: #fff; }',
      '.btn-accent:hover { background: var(--ach); }',
      '.btn-sec { background: #2d2d2d; color: #ddd; border: 1px solid #444; }',
      '.btn-sec:hover { background: #3c3c3c; color: #fff; }',
      '.btn-ai-label { font-size: 9px; font-weight: 400; opacity: .7; margin-left: 4px; }',
      '.scan-box { text-align: center; padding: 36px 12px; }',
      '.scan-ring { width: 48px; height: 48px; margin: 0 auto 14px; border: 3px solid #333; border-top-color: var(--ac); border-radius: 50%; animation: spin .8s linear infinite; }',
      '@keyframes spin { to { transform: rotate(360deg); } }',
      '.scan-file-anim { display: inline-block; animation: pulse 1.5s ease-in-out infinite; font-size: 13px; color: #9cdcfe; font-weight: 600; margin-top: 6px; }',
      '@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .4; } }',
      '.scan-text { font-size: 12px; color: #888; margin-top: 4px; }',
      '.empty-state { text-align: center; padding: 28px 12px; }',
      '.empty-icon { font-size: 32px; margin-bottom: 8px; }',
      '.empty-title { font-size: 13px; font-weight: 600; margin-bottom: 4px; }',
      '.empty-sub { font-size: 11.5px; color: #888; }',
      '.card { background: #222; border: 1px solid var(--bdr); border-radius: 8px; margin-bottom: 10px; overflow: hidden; }',
      '.card-hdr { padding: 8px 10px; display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #2a2a2a; gap: 8px; }',
      '.card-hdr-left { flex: 1; min-width: 0; }',
      '.card-title { font-size: 12px; font-weight: 600; margin-bottom: 2px; }',
      '.card-meta { font-size: 10px; color: #888; }',
      '.badge { font-size: 9px; font-weight: 700; padding: 2px 6px; border-radius: 3px; white-space: nowrap; flex-shrink: 0; margin-top: 2px; }',
      '.badge-gray { background: #444; color: #bbb; }',
      '.badge-green { background: #2e7d3233; color: #81c784; border: 1px solid #81c784; }',
      '.badge-orange { background: #ef6c0033; color: #ffb74d; border: 1px solid #ffb74d; }',
      '.badge-blue { background: #0e639c44; color: #64b5f6; border: 1px solid #64b5f6; }',
      '.badge-spin { display: inline-block; animation: spin .8s linear infinite; }',
      '.card-body { padding: 8px 10px; font-size: 11.5px; line-height: 1.5; }',
      '.card-body pre { background: #111; border: 1px solid #333; border-radius: 4px; padding: 6px 8px; margin: 6px 0; overflow-x: auto; font-size: 11px; font-family: var(--vscode-editor-font-family, Consolas, monospace); color: #d4d4d4; max-height: 180px; }',
      '.card-actions { padding: 6px 10px; border-top: 1px solid #2a2a2a; display: flex; gap: 6px; flex-wrap: wrap; background: #1c1c1c; }',
      '.btn-card { border: none; border-radius: 4px; padding: 5px 10px; font-size: 10.5px; font-weight: 600; cursor: pointer; }',
      '.btn-card-primary { background: var(--ok); color: #fff; }',
      '.btn-card-primary:hover { background: #43a047; }',
      '.btn-card-primary:disabled { background: #444; color: #777; cursor: not-allowed; }',
      '.btn-card-accent { background: var(--ac); color: #fff; }',
      '.btn-card-accent:hover { background: var(--ach); }',
      '.btn-card-outline { background: transparent; border: 1px solid #555; color: #ccc; }',
      '.btn-card-outline:hover { background: #333; color: #fff; }',
      '.btn-card-warn { background: var(--warn); color: #fff; }',
      '.btn-card-warn:hover { background: #f57c00; }',
      '.verify-detail { font-size: 10.5px; color: #ffb74d; margin-top: 4px; padding: 4px 6px; background: #2a1f0f; border-radius: 3px; border-left: 2px solid var(--warn); }',
      '.proj-file { background: #191919; border: 1px solid #333; border-radius: 6px; padding: 8px 10px; margin-bottom: 6px; display: flex; justify-content: space-between; align-items: center; cursor: pointer; }',
      '.proj-file:hover { border-color: #555; }',
      '.proj-file-name { font-weight: 600; font-size: 12px; }',
      '.proj-file-count { font-size: 11px; color: #ffa726; }',
      '.progress-bar { background: #1a1a1a; border: 1px solid var(--bdr); border-radius: 4px; padding: 6px 10px; margin-bottom: 8px; font-size: 11px; color: #aaa; display: flex; align-items: center; gap: 6px; }',
      '.progress-bar .mini-spin { width: 12px; height: 12px; border: 2px solid #333; border-top-color: var(--ac); border-radius: 50%; animation: spin .8s linear infinite; flex-shrink: 0; }'
    ];
    var css = cssLines.join('\n');

    var jsLines = [
      'var vscodeApi = acquireVsCodeApi();',
      'var elFileName = document.getElementById("elFileName");',
      'var elContent = document.getElementById("content");',
      'var S = {};',
      'vscodeApi.postMessage({ action: "ready" });',
      'window.addEventListener("message", function(e) {',
      '  if (e.data.type === "state") { S = e.data.state; render(); }',
      '});',
      'document.addEventListener("click", function(e) {',
      '  var btn = e.target.closest("[data-action]");',
      '  if (!btn) return;',
      '  var action = btn.getAttribute("data-action");',
      '  var index = btn.getAttribute("data-index");',
      '  var filePath = btn.getAttribute("data-filepath");',
      '  var msg = { action: action };',
      '  if (index !== null && index !== undefined) msg.index = parseInt(index, 10);',
      '  if (filePath) msg.filePath = filePath;',
      '  vscodeApi.postMessage(msg);',
      '});',
      'function esc(t) {',
      '  if (!t) return "";',
      '  return t.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");',
      '}',
      'function diffLines(oldLines, newLines) {',
      '  var matrix = [];',
      '  for (var i = 0; i <= oldLines.length; i++) {',
      '    matrix[i] = [];',
      '    for (var j = 0; j <= newLines.length; j++) {',
      '      if (i === 0 || j === 0) matrix[i][j] = 0;',
      '      else if (oldLines[i-1] === newLines[j-1]) matrix[i][j] = matrix[i-1][j-1] + 1;',
      '      else matrix[i][j] = Math.max(matrix[i-1][j], matrix[i][j-1]);',
      '    }',
      '  }',
      '  var result = [];',
      '  var x = oldLines.length;',
      '  var y = newLines.length;',
      '  while (x > 0 || y > 0) {',
      '    if (x > 0 && y > 0 && oldLines[x-1] === newLines[y-1]) {',
      '      result.unshift({ type: "common", text: oldLines[x-1] });',
      '      x--; y--;',
      '    } else if (y > 0 && (x === 0 || matrix[x][y-1] >= matrix[x-1][y])) {',
      '      result.unshift({ type: "added", text: newLines[y-1] });',
      '      y--;',
      '    } else if (x > 0 && (y === 0 || matrix[x][y-1] < matrix[x-1][y])) {',
      '      result.unshift({ type: "removed", text: oldLines[x-1] });',
      '      x--;',
      '    }',
      '  }',
      '  return result;',
      '}',
      'function renderHistory() {',
      '  if (!S.history || S.history.length === 0) return \'<div style="text-align:center;padding:12px;color:#666;font-size:11px;">No history yet.</div>\';',
      '  var h = "";',
      '  for (var hi = S.history.length - 1; hi >= 0; hi--) {',
      '    var entry = S.history[hi];',
      '    var d = new Date(entry.timestamp);',
      '    var time = d.toLocaleDateString() + " " + d.toLocaleTimeString();',
      '    var icon = entry.action === "fix" ? "\\u{1F527}" : "\\u{1F50D}";',
      '    var acLabel = entry.action === "fix" ? "Fix (AI)" : "Diagnose";',
      '    var issueColor = entry.issuesFound > 0 ? "#ffa726" : "#81c784";',
      '    h += \'<div style="background:#1a1a1a;border:1px solid #333;border-radius:6px;padding:6px 8px;margin-bottom:4px;font-size:10.5px;cursor:pointer;" data-action="openFile" data-filepath="\' + esc(entry.filePath) + \'">\';',
      '    h += \'<div style="display:flex;justify-content:space-between;align-items:center;"><span>\' + icon + \' <b>\' + acLabel + \'</b> \\u2014 \' + esc(entry.fileName) + \'</span><span style="color:#666;">\' + esc(time) + \'</span></div>\';',
      '    h += \'<div style="color:\' + issueColor + \';margin-top:2px;">\' + esc(entry.explanation) + \'</div>\';',
      '    h += \'</div>\';',
      '  }',
      '  return h;',
      '}',
      'function render() {',
      '  elFileName.innerText = S.activeFileName || "No file selected";',
      '  if (S.page === "idle") {',
      '    var idleHtml = \'<div class="empty-state"><div class="empty-icon">&#x1f6e1;&#xfe0f;</div><div class="empty-title">SeniorDev AI Ready</div><div class="empty-sub"><b>Diagnose File</b> finds errors.<br/><b>Fix File (AI)</b> fixes and edits the file directly.</div></div>\';',
      '    idleHtml += \'<div style="margin-top:8px;"><div style="display:flex;justify-content:space-between;align-items:center;padding:4px 0;"><span style="font-size:11px;font-weight:600;color:#9cdcfe;">History</span><button class="btn-sm" data-action="clearHistory" style="font-size:9px;padding:2px 6px;">Clear</button></div>\';',
      '    idleHtml += renderHistory();',
      '    idleHtml += \'</div>\';',
      '    elContent.innerHTML = idleHtml;',
      '    return;',
      '  }',
      '  if (S.page === "scanning") {',
      '    var fileAnim = S.activeFileName ? \'<div class="scan-file-anim">\' + esc(S.activeFileName) + \'</div>\' : \'\';',
      '    elContent.innerHTML = \'<div class="scan-box"><div class="scan-ring"></div>\' + fileAnim + \'<div class="scan-text">\' + esc(S.scanProgress || "Analyzing...") + \'</div></div>\';',
      '    return;',
      '  }',
      '  if (S.page === "error") {',
      '    elContent.innerHTML = \'<div class="card" style="border-left:3px solid var(--err);"><div class="card-hdr"><div class="card-hdr-left"><div class="card-title" style="color:#ff8a80;">Error</div></div></div><div class="card-body">\' + esc(S.errorText) + \'</div></div>\';',
      '    return;',
      '  }',
      '  if (S.page === "project_results" && S.projectSummary) {',
      '    var ps = S.projectSummary;',
      '    var filesHtml = "";',
      '    for (var fi = 0; fi < ps.files.length; fi++) {',
      '      var f = ps.files[fi];',
      '      filesHtml += \'<div class="proj-file" data-action="fixFile" data-filepath="\' + esc(f.filePath) + \'"><div><div class="proj-file-name">\' + esc(f.fileName) + \'</div><div class="proj-file-count">\' + f.issueCount + \' issue(s)</div></div><button class="btn-card btn-card-accent" data-action="fixFile" data-filepath="\' + esc(f.filePath) + \'" style="font-size:10px;padding:3px 8px;">Fix (AI)</button></div>\';',
      '    }',
      '    elContent.innerHTML = \'<div class="card"><div class="card-hdr"><div class="card-hdr-left"><div class="card-title">Project Overview</div><div class="card-meta">Build: \' + esc(ps.buildSystem) + \' | \' + ps.totalIssues + \' total issue(s) in \' + ps.files.length + \' file(s)</div></div></div><div class="card-body">\' + filesHtml + \'</div></div>\';',
      '    return;',
      '  }',
      '  if (S.page === "file_results") {',
      '    var html = "";',
      '    if (S.scanProgress) { html += \'<div class="progress-bar"><div class="mini-spin"></div>\' + esc(S.scanProgress) + \'</div>\'; }',
      '    if (S.unifiedFix) {',
      '      var uf = S.unifiedFix;',
      '      var badge = uf.accepted ? \'<span class="badge badge-green">Accepted</span>\' : \'<span class="badge badge-orange">Applied (Pending Accept)</span>\';',
      '      html += \'<div class="card" style="border-left:3px solid var(--ac);"><div class="card-hdr"><div class="card-hdr-left"><div class="card-title">SeniorDev AI Unified Fix</div><div class="card-meta">All \' + uf.issuesCount + \' issue(s) addressed in single replacement</div></div>\' + badge + \'</div>\';',
      '      html += \'<div class="card-body">\';',
      '      html += \'<div style="font-size:11.5px;margin-bottom:8px;line-height:1.45;">\' + esc(uf.explanation) + \'</div>\';',
      '      if (uf.errorsFound && uf.errorsFound.length > 0) {',
      '        html += \'<div style="margin-bottom:8px;">\';',
      '        html += \'<div style="font-size:10.5px;font-weight:600;color:#9cdcfe;margin-bottom:4px;">Errors Found:</div>\';',
      '        for (var ei = 0; ei < uf.errorsFound.length; ei++) {',
      '          var err = uf.errorsFound[ei];',
      '          html += \'<div style="font-size:10.5px;padding:3px 6px;margin-bottom:2px;background:#2a1f0f;border-left:2px solid #ffa726;border-radius:2px;color:#ffb74d;"><b>Line \' + err.line + \':</b> \' + esc(err.message) + \'</div>\';',
      '        }',
      '        html += \'</div>\';',
      '      }',
      '      var oldL = (uf.originalContent || "").replace(/\\r/g, "").split("\\n");',
      '      var newL = (uf.fixedContent || "").replace(/\\r/g, "").split("\\n");',
      '      var diff = diffLines(oldL, newL);',
      '      var addedCount = 0; var removedCount = 0;',
      '      for (var d = 0; d < diff.length; d++) {',
      '        if (diff[d].type === "added") addedCount++;',
      '        else if (diff[d].type === "removed") removedCount++;',
      '      }',
      '      html += \'<div style="font-size:10.5px;font-weight:600;margin-bottom:6px;display:flex;justify-content:space-between;align-items:center;background:#181818;padding:4px 8px;border-radius:4px;border:1px solid #333;">\';',
      '      html += \'  <div style="display:flex;gap:12px;">\';',
      '      html += \'    <span style="color:#81c784;">🟢 +\' + addedCount + \' Added / Modified</span>\';',
      '      html += \'    <span style="color:#ff8a80;">🔴 -\' + removedCount + \' Removed / Replaced</span>\';',
      '      html += \'  </div>\';',
      '      html += \'  <span style="color:#888;font-size:9.5px;">Code Diff</span>\';',
      '      html += \'</div>\';',
      '      html += \'<pre style="background:#0e0e0e;padding:6px 8px;border:1px solid #333;border-radius:4px;max-height:260px;overflow-y:auto;font-family:var(--vscode-editor-font-family, Consolas, monospace);font-size:11px;line-height:1.45;color:#ccc;">\';',
      '      for (var d = 0; d < diff.length; d++) {',
      '        var line = diff[d];',
      '        if (line.type === "added") {',
      '          html += \'<div style="color:#81c784;background:#16381d;padding:2px 6px;margin:1px 0;border-left:3px solid #43a047;font-weight:600;white-space:pre;">🟢 + \' + esc(line.text) + \'</div>\';',
      '        } else if (line.type === "removed") {',
      '          html += \'<div style="color:#ff8a80;background:#3c1515;padding:2px 6px;margin:1px 0;border-left:3px solid #e57373;font-weight:600;white-space:pre;">🔴 - \' + esc(line.text) + \'</div>\';',
      '        } else {',
      '          html += \'<div style="color:#777;padding:1px 6px;white-space:pre;">    \' + esc(line.text) + \'</div>\';',
      '        }',
      '      }',
      '      html += \'</pre>\';',
      '      html += \'</div>\';',
      '      html += \'<div class="card-actions">\';',
      '      if (!uf.accepted) {',
      '        html += \'<button class="btn-card btn-card-primary" data-action="acceptFix">Accept Fix</button>\';',
      '        html += \'<button class="btn-card btn-card-warn" data-action="undoFix">Undo / Reject</button>\';',
      '      } else {',
      '        html += \'<button class="btn-card btn-card-warn" data-action="undoFix">Revert Fix</button>\';',
      '      }',
      '      html += \'<button class="btn-card btn-card-accent" data-action="showUnifiedDiff">Compare Diff (Native)</button>\';',
      '      html += \'<button class="btn-card btn-card-outline" data-action="diagnose">Diagnose File</button>\';',
      '      html += \'</div></div>\';',
      '      elContent.innerHTML = html; return;',
      '    }',
      '    if (!S.cards || S.cards.length === 0) {',
      '      html += \'<div class="empty-state"><div class="empty-icon">&#x2705;</div><div class="empty-title">No Issues Found</div><div class="empty-sub">\' + esc(S.activeFileName || "File") + \' passes all checks.</div></div>\';',
      '      elContent.innerHTML = html; return;',
      '    }',
      '    html += \'<div class="card" style="border-left:3px solid #ffa726;"><div class="card-hdr"><div class="card-hdr-left"><div class="card-title">Diagnose Results</div><div class="card-meta">\' + S.cards.length + \' issue(s) found in \' + esc(S.activeFileName || "file") + \'</div></div><span class="badge badge-orange">\' + S.cards.length + \' Issues</span></div>\';',
      '    html += \'<div class="card-body">\';',
      '    for (var idx = 0; idx < S.cards.length; idx++) {',
      '      var c = S.cards[idx]; var issue = c.issue;',
      '      var sevColor = "#888";',
      '      if (issue.severity === "CRITICAL" || issue.severity === "HIGH") sevColor = "#ef5350";',
      '      else if (issue.severity === "MEDIUM") sevColor = "#ffa726";',
      '      html += \'<div style="padding:4px 6px;margin-bottom:3px;background:#1a1a1a;border-left:2px solid \' + sevColor + \';border-radius:2px;font-size:10.5px;">\';',
      '      html += \'<b style="color:\' + sevColor + \';">Line \' + issue.line + \':</b> \' + esc(issue.message);',
      '      html += \' <span style="color:#666;font-size:9px;">[\' + esc(issue.severity) + \' / \' + esc(issue.type) + \']</span>\';',
      '      html += \'</div>\';',
      '    }',
      '    html += \'</div>\';',
      '    html += \'<div class="card-actions"><button class="btn-card btn-card-accent" data-action="fixAI">Fix All (AI)</button></div>\';',
      '    html += \'</div>\';',
      '    elContent.innerHTML = html;',
      '  }',
      '}'
    ];
    var js = jsLines.join('\n');

    var htmlLines = [
      '<!DOCTYPE html>',
      '<html lang="en">',
      '<head>',
      '<meta charset="UTF-8">',
      "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';\">",
      '<meta name="viewport" content="width=device-width, initial-scale=1.0">',
      '<title>SeniorDev AI</title>',
      '<style>' + css + '</style>',
      '</head>',
      '<body>',
      '<div class="top-bar">',
      '  <div class="file-row">',
      '    <div class="file-name" id="elFileName">No file selected</div>',
      '    <button class="btn-sm" data-action="pick">Change File</button>',
      '  </div>',
      '  <div class="action-row">',
      '    <button class="btn btn-sec" data-action="diagnose">Diagnose File</button>',
      '    <button class="btn btn-accent" data-action="fixAI">Fix File<span class="btn-ai-label">(AI)</span></button>',
      '  </div>',
      '</div>',
      '<div id="content"></div>',
      '<script>' + js + '</script>',
      '</body>',
      '</html>'
    ];
    return htmlLines.join('\n');
  }
}
