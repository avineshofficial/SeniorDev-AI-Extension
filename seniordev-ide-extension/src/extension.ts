import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { BackendClient } from './client/backendClient';
import { ChatViewProvider } from './ui/ChatViewProvider';
import { IssueTreeViewProvider, IssueNode } from './ui/IssueTreeViewProvider';
import { IssueDetailPanel } from './ui/IssueDetailPanel';
import { Issue, WebviewMessage } from './types';

export function activate(context: vscode.ExtensionContext) {
  console.log('SeniorDev AI extension activated.');

  const backendClient = new BackendClient();
  const treeViewProvider = new IssueTreeViewProvider();
  const chatViewProvider = new ChatViewProvider(context.extensionUri, backendClient);

  // Register Webview Chat View in Sidebar
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(
      ChatViewProvider.viewType,
      chatViewProvider,
      { webviewOptions: { retainContextWhenHidden: true } }
    )
  );

  // Register Tree View with checkbox support
  const treeView = vscode.window.createTreeView('seniordev.issueTree', {
    treeDataProvider: treeViewProvider,
    showCollapseAll: true
  });

  // Wire checkbox state changes
  treeView.onDidChangeCheckboxState(e => {
    for (const [item, state] of e.items) {
      if (item instanceof IssueNode) {
        treeViewProvider.handleCheckboxChange(item, state);
      }
    }
  });

  context.subscriptions.push(treeView);

  // === Commands ===

  // Fix Active File with AI
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.fixActiveFile', async () => {
      await chatViewProvider.fixActiveFile();
    })
  );

  // Scan Entire Project
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.analyzeProject', async () => {
      await chatViewProvider.scanProject();
    })
  );

  // Refresh / Diagnose
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.refresh', async () => {
      await chatViewProvider.diagnoseFile();
    })
  );

  // Click-to-Navigate: Select an issue and jump to its exact file:line
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.selectIssue', async (issue: Issue) => {
      if (!issue || !issue.filePath) {
        return;
      }

      // Resolve file path
      const wsRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
      let absPath = issue.filePath;
      if (!path.isAbsolute(absPath)) {
        absPath = path.join(wsRoot, absPath);
      }

      if (!fs.existsSync(absPath)) {
        vscode.window.showWarningMessage(`File not found: ${issue.filePath}`);
        return;
      }

      const uri = vscode.Uri.file(absPath);
      const doc = await vscode.workspace.openTextDocument(uri);
      const line = Math.max(0, (issue.line || 1) - 1);
      const range = new vscode.Range(line, 0, line, 0);
      await vscode.window.showTextDocument(doc, {
        selection: range,
        preserveFocus: false
      });

      // Also show the issue detail panel
      IssueDetailPanel.createOrShow(
        context.extensionUri,
        issue,
        wsRoot,
        (msg: WebviewMessage, panel: IssueDetailPanel) => {
          handleDetailPanelMessage(msg, panel, backendClient, treeViewProvider);
        }
      );
    })
  );

  // Inline Diff: Show VS Code's native side-by-side diff for an issue
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.showInlineDiff', async (issue: Issue) => {
      if (!issue || !issue.snippet || !issue.aiFixCode) {
        vscode.window.showInformationMessage('No AI fix available for diff view. Run "Fix File (AI)" first.');
        return;
      }

      const originalUri = vscode.Uri.parse('seniordev-original:' + issue.id);
      const fixedUri = vscode.Uri.parse('seniordev-fixed:' + issue.id);

      // Register content providers for the diff
      const originalProvider = new (class implements vscode.TextDocumentContentProvider {
        provideTextDocumentContent(): string {
          return issue.snippet || '';
        }
      })();

      const fixedProvider = new (class implements vscode.TextDocumentContentProvider {
        provideTextDocumentContent(): string {
          return issue.aiFixCode || '';
        }
      })();

      context.subscriptions.push(
        vscode.workspace.registerTextDocumentContentProvider('seniordev-original', originalProvider),
        vscode.workspace.registerTextDocumentContentProvider('seniordev-fixed', fixedProvider)
      );

      await vscode.commands.executeCommand('vscode.diff',
        originalUri,
        fixedUri,
        `Original ↔ AI Fix (Line ${issue.line})`,
        { preview: true }
      );
    })
  );

  // Enrich selected issues from tree view
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.enrichSelectedIssues', async () => {
      const selectedIds = treeViewProvider.getSelectedIssueIds();
      if (selectedIds.length === 0) {
        vscode.window.showInformationMessage('No issues selected. Use checkboxes in the tree view to select issues.');
        return;
      }

      vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'SeniorDev AI: Enriching issues...' },
        async () => {
          try {
            // We need a sessionId — get it from the chat view
            const sessionId = chatViewProvider.getSessionId();
            if (!sessionId) {
              vscode.window.showWarningMessage('Run "Scan Project" first to create a session.');
              return;
            }
            const enriched = await backendClient.enrichIssues(sessionId, selectedIds);
            treeViewProvider.updateIssues(enriched);
            vscode.window.showInformationMessage(`Enriched ${enriched.length} issue(s) with AI explanations.`);
          } catch (err: any) {
            vscode.window.showErrorMessage('Enrichment failed: ' + err.message);
          }
        }
      );
    })
  );

  // Verify selected issues from tree view
  context.subscriptions.push(
    vscode.commands.registerCommand('seniordev.verifySelectedIssues', async () => {
      const selectedIds = treeViewProvider.getSelectedIssueIds();
      if (selectedIds.length === 0) {
        vscode.window.showInformationMessage('No issues selected. Use checkboxes in the tree view to select issues.');
        return;
      }

      vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'SeniorDev AI: Verifying fixes...' },
        async () => {
          try {
            const sessionId = chatViewProvider.getSessionId();
            const projectRoot = treeViewProvider.getProjectRoot();
            if (!sessionId || !projectRoot) {
              vscode.window.showWarningMessage('Run "Scan Project" first to create a session.');
              return;
            }
            const verified = await backendClient.verifyIssues(sessionId, selectedIds, projectRoot);
            treeViewProvider.updateIssues(verified);
            const passed = verified.filter(i => i.verificationStatus === 'VERIFIED_FIXED').length;
            vscode.window.showInformationMessage(`Verification complete: ${passed}/${verified.length} fixes passed.`);
          } catch (err: any) {
            vscode.window.showErrorMessage('Verification failed: ' + err.message);
          }
        }
      );
    })
  );
}

/**
 * Handles messages from the IssueDetailPanel webview.
 */
async function handleDetailPanelMessage(
  msg: WebviewMessage,
  panel: IssueDetailPanel,
  backendClient: BackendClient,
  treeViewProvider: IssueTreeViewProvider
): Promise<void> {
  switch (msg.command) {
    case 'openFile': {
      if (msg.filePath) {
        const wsRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
        let absPath = msg.filePath;
        if (!path.isAbsolute(absPath)) {
          absPath = path.join(wsRoot, absPath);
        }
        const uri = vscode.Uri.file(absPath);
        const doc = await vscode.workspace.openTextDocument(uri);
        const line = Math.max(0, (msg.line || 1) - 1);
        await vscode.window.showTextDocument(doc, {
          selection: new vscode.Range(line, 0, line, 0)
        });
      }
      break;
    }

    case 'enrichIssue': {
      if (msg.issueId) {
        const issue = treeViewProvider.getIssueById(msg.issueId);
        if (issue) {
          try {
            // Find session — simplified: use first available
            const allIds = treeViewProvider.getAllIssueIds();
            // This requires the sessionId to be available somewhere — for now, show a message
            vscode.window.showInformationMessage('Use "Fix File (AI)" from the sidebar to enrich this issue.');
          } catch (err: any) {
            vscode.window.showErrorMessage('Enrichment failed: ' + err.message);
          }
        }
      }
      break;
    }

    case 'verifyIssue': {
      vscode.window.showInformationMessage('Use "Verify Selected Issues" from the tree view to verify fixes.');
      break;
    }

    case 'applyFix': {
      const issue = panel.getCurrentIssue();
      if (issue && issue.aiFixCode && msg.filePath) {
        const wsRoot = panel.getProjectRoot();
        let absPath = msg.filePath;
        if (!path.isAbsolute(absPath)) {
          absPath = path.join(wsRoot, absPath);
        }

        if (fs.existsSync(absPath)) {
          const content = fs.readFileSync(absPath, 'utf-8');
          if (issue.snippet && content.includes(issue.snippet)) {
            const newContent = content.replace(issue.snippet, issue.aiFixCode);
            const uri = vscode.Uri.file(absPath);
            const doc = await vscode.workspace.openTextDocument(uri);
            const fullRange = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
            const edit = new vscode.WorkspaceEdit();
            edit.replace(uri, fullRange, newContent);
            const ok = await vscode.workspace.applyEdit(edit);
            if (ok) {
              await doc.save();
              vscode.window.showInformationMessage('Fix applied successfully.');
            }
          } else {
            vscode.window.showWarningMessage('Could not find original code snippet in file.');
          }
        }
      }
      break;
    }
  }
}

export function deactivate() {}