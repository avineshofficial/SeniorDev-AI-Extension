import * as vscode from 'vscode';
import {
  AnalyzeRequest,
  AnalyzeResponse,
  ChatRequest,
  ChatResponse,
  EnrichRequest,
  HealthResponse,
  Issue,
  VerifyRequest,
  WholeFileFixRequest,
  WholeFileFixResponse
} from '../types';

export class BackendClient {
  private get baseUrl(): string {
    const config = vscode.workspace.getConfiguration('seniordev');
    return config.get<string>('backendUrl', 'http://localhost:8080').replace(/\/+$/, '');
  }

  public async checkHealth(): Promise<HealthResponse> {
    const response = await fetch(`${this.baseUrl}/api/health`);
    if (!response.ok) {
      throw new Error(`Health check failed with HTTP ${response.status}: ${response.statusText}`);
    }
    return (await response.json()) as HealthResponse;
  }

  public async analyzeProject(projectRoot: string, targetFilePath?: string): Promise<AnalyzeResponse> {
    const req: AnalyzeRequest = { projectRoot, targetFilePath };
    const response = await fetch(`${this.baseUrl}/api/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Analysis failed (HTTP ${response.status}): ${errorText || response.statusText}`);
    }

    return (await response.json()) as AnalyzeResponse;
  }

  public async enrichIssues(sessionId: string, issueIds?: string[]): Promise<Issue[]> {
    const req: EnrichRequest = { sessionId, issueIds };
    const response = await fetch(`${this.baseUrl}/api/enrich`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Enrichment failed (HTTP ${response.status}): ${errorText || response.statusText}`);
    }

    return (await response.json()) as Issue[];
  }

  public async verifyIssues(sessionId: string, issueIds: string[], projectRoot: string): Promise<Issue[]> {
    const req: VerifyRequest = { sessionId, issueIds, projectRoot };
    const response = await fetch(`${this.baseUrl}/api/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Verification failed (HTTP ${response.status}): ${errorText || response.statusText}`);
    }

    return (await response.json()) as Issue[];
  }

  public async getIssues(sessionId: string): Promise<Issue[]> {
    const response = await fetch(`${this.baseUrl}/api/issues?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch issues (HTTP ${response.status})`);
    }
    return (await response.json()) as Issue[];
  }

  /**
   * Sends a free-form question to the AI chat endpoint.
   */
  public async chat(question: string, sessionId?: string, filePath?: string, fileContent?: string): Promise<ChatResponse> {
    const req: ChatRequest = { question, sessionId, filePath, fileContent };
    const response = await fetch(`${this.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Chat failed (HTTP ${response.status}): ${errorText || response.statusText}`);
    }

    return (await response.json()) as ChatResponse;
  }

  public async fixWholeFile(req: WholeFileFixRequest): Promise<WholeFileFixResponse> {
    const response = await fetch(`${this.baseUrl}/api/fix-file`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Whole file fix failed (HTTP ${response.status}): ${errorText || response.statusText}`);
    }

    return (await response.json()) as WholeFileFixResponse;
  }
}

