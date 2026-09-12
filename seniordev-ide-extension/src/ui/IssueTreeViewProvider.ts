import * as vscode from 'vscode';
import * as path from 'path';
import { Issue, Severity, VerificationStatus } from '../types';

export type TreeNode = FileNode | IssueNode;

export class FileNode extends vscode.TreeItem {
  constructor(
    public readonly filePath: string,
    public readonly issues: Issue[],
    public readonly projectRoot: string
  ) {
    super(
      path.basename(filePath),
      vscode.TreeItemCollapsibleState.Expanded
    );

    const relPath = path.isAbsolute(filePath)
      ? path.relative(projectRoot, filePath)
      : filePath;

    this.description = `(${issues.length}) - ${path.dirname(relPath)}`;
    this.tooltip = `${issues.length} issue(s) in ${relPath}`;
    this.resourceUri = vscode.Uri.file(
      path.isAbsolute(filePath) ? filePath : path.join(projectRoot, filePath)
    );
    this.contextValue = 'fileNode';
  }
}

export class IssueNode extends vscode.TreeItem {
  constructor(
    public readonly issue: Issue,
    public readonly projectRoot: string,
    public isChecked: boolean = false
  ) {
    super(
      `Line ${issue.line}: ${issue.message}`,
      vscode.TreeItemCollapsibleState.None
    );

    this.id = issue.id;
    this.description = this.buildDescription(issue);
    this.tooltip = this.buildTooltip(issue);
    this.iconPath = this.getIcon(issue);
    this.contextValue = 'issueItem';
    this.checkboxState = isChecked
      ? vscode.TreeItemCheckboxState.Checked
      : vscode.TreeItemCheckboxState.Unchecked;

    const absPath = path.isAbsolute(issue.filePath)
      ? issue.filePath
      : path.join(projectRoot, issue.filePath);

    this.command = {
      command: 'seniordev.selectIssue',
      title: 'View Issue Details',
      arguments: [issue]
    };
  }

  private buildDescription(issue: Issue): string {
    const parts: string[] = [issue.severity];
    if (issue.verificationStatus === 'VERIFIED_FIXED') {
      parts.push('[Verified Fixed]');
    } else if (issue.verificationStatus === 'VERIFICATION_FAILED') {
      parts.push('[Verification Failed]');
    } else if (issue.aiExplanation) {
      parts.push('[AI Ready]');
    }
    return parts.join(' ');
  }

  private buildTooltip(issue: Issue): vscode.MarkdownString {
    const md = new vscode.MarkdownString();
    md.isTrusted = true;
    md.appendMarkdown(`**${issue.severity}** | \`${issue.language}\` | \`${issue.type}\`\n\n`);
    md.appendMarkdown(`**Message:** ${issue.message}\n\n`);
    md.appendMarkdown(`**File:** \`${issue.filePath}:${issue.line}\`\n\n`);

    if (issue.aiExplanation) {
      md.appendMarkdown(`---\n**AI Explanation:**\n${issue.aiExplanation}\n\n`);
    }

    if (issue.verificationStatus !== 'NOT_VERIFIED') {
      md.appendMarkdown(`**Verification:** \`${issue.verificationStatus}\`\n`);
      if (issue.verificationDetail) {
        md.appendMarkdown(`*Detail:* ${issue.verificationDetail}\n`);
      }
    }

    return md;
  }

  private getIcon(issue: Issue): vscode.ThemeIcon {
    if (issue.verificationStatus === 'VERIFIED_FIXED') {
      return new vscode.ThemeIcon('pass-filled', new vscode.ThemeColor('testing.iconPassed'));
    }
    if (issue.verificationStatus === 'VERIFICATION_FAILED') {
      return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
    }
    if (issue.aiExplanation) {
      return new vscode.ThemeIcon('sparkle', new vscode.ThemeColor('charts.purple'));
    }

    switch (issue.severity) {
      case 'CRITICAL':
      case 'HIGH':
        return new vscode.ThemeIcon('error', new vscode.ThemeColor('problemsErrorIcon.foreground'));
      case 'MEDIUM':
        return new vscode.ThemeIcon('warning', new vscode.ThemeColor('problemsWarningIcon.foreground'));
      case 'LOW':
      case 'INFO':
      default:
        return new vscode.ThemeIcon('info', new vscode.ThemeColor('problemsInfoIcon.foreground'));
    }
  }
}

export class IssueTreeViewProvider implements vscode.TreeDataProvider<TreeNode> {
  private _onDidChangeTreeData = new vscode.EventEmitter<TreeNode | undefined | null | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private issues: Issue[] = [];
  private projectRoot: string = '';
  private selectedIssueIds = new Set<string>();

  public setIssues(issues: Issue[], projectRoot: string): void {
    this.issues = issues;
    this.projectRoot = projectRoot;
    this._onDidChangeTreeData.fire();
  }

  public updateIssue(updatedIssue: Issue): void {
    const index = this.issues.findIndex(i => i.id === updatedIssue.id);
    if (index !== -1) {
      this.issues[index] = updatedIssue;
      this._onDidChangeTreeData.fire();
    }
  }

  public updateIssues(updatedIssues: Issue[]): void {
    const map = new Map(updatedIssues.map(i => [i.id, i]));
    for (let i = 0; i < this.issues.length; i++) {
      if (map.has(this.issues[i].id)) {
        this.issues[i] = map.get(this.issues[i].id)!;
      }
    }
    this._onDidChangeTreeData.fire();
  }

  public getSelectedIssueIds(): string[] {
    return Array.from(this.selectedIssueIds);
  }

  public getAllIssueIds(): string[] {
    return this.issues.map(i => i.id);
  }

  public getIssueById(id: string): Issue | undefined {
    return this.issues.find(i => i.id === id);
  }

  public getIssuesList(): Issue[] {
    return this.issues;
  }

  public getProjectRoot(): string {
    return this.projectRoot;
  }

  public handleCheckboxChange(node: IssueNode, newState: vscode.TreeItemCheckboxState): void {
    if (newState === vscode.TreeItemCheckboxState.Checked) {
      this.selectedIssueIds.add(node.issue.id);
    } else {
      this.selectedIssueIds.delete(node.issue.id);
    }
  }

  getTreeItem(element: TreeNode): vscode.TreeItem {
    return element;
  }

  getChildren(element?: TreeNode): Thenable<TreeNode[]> {
    if (!this.issues || this.issues.length === 0) {
      return Promise.resolve([]);
    }

    if (!element) {
      // Root level: Group by filePath
      const fileMap = new Map<string, Issue[]>();
      for (const issue of this.issues) {
        const list = fileMap.get(issue.filePath) || [];
        list.push(issue);
        fileMap.set(issue.filePath, list);
      }

      const fileNodes: FileNode[] = [];
      for (const [filePath, fileIssues] of fileMap.entries()) {
        fileNodes.push(new FileNode(filePath, fileIssues, this.projectRoot));
      }
      return Promise.resolve(fileNodes);
    }

    if (element instanceof FileNode) {
      // Child level: issues inside this file sorted by line number
      const sorted = [...element.issues].sort((a, b) => a.line - b.line);
      const issueNodes = sorted.map(
        issue => new IssueNode(issue, this.projectRoot, this.selectedIssueIds.has(issue.id))
      );
      return Promise.resolve(issueNodes);
    }

    return Promise.resolve([]);
  }
}
