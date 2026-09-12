export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';

export type IssueType =
  | 'COMPILE_ERROR'
  | 'LINT_WARNING'
  | 'CODE_SMELL'
  | 'DESIGN_ISSUE'
  | 'LOGIC_ISSUE'
  | 'TEST_FAILURE'
  | 'SECURITY_RISK';

export type VerificationStatus =
  | 'NOT_VERIFIED'
  | 'VERIFIED_FIXED'
  | 'VERIFICATION_FAILED'
  | 'VERIFICATION_SKIPPED';

export interface Issue {
  id: string;
  language: string;
  type: IssueType;
  severity: Severity;
  filePath: string;
  line: number;
  message: string;
  snippet: string | null;
  suggestion: string | null;
  aiExplanation: string | null;
  aiFixCode: string | null;
  verificationStatus: VerificationStatus;
  verificationDetail: string | null;
  relatedFiles: string[];
}

export interface AnalyzeRequest {
  projectRoot: string;
  targetFilePath?: string;
}

export interface AnalyzeResponse {
  sessionId: string;
  projectRoot: string;
  issues: Issue[];
  buildSystem: string;
  classpathResolved: boolean;
  totalIssues: number;
}

export interface EnrichRequest {
  sessionId: string;
  issueIds?: string[];
}

export interface VerifyRequest {
  sessionId: string;
  issueIds: string[];
  projectRoot: string;
}

export interface HealthResponse {
  status: string;
  backend: string;
  javaCompiler: boolean;
}

export interface WebviewMessage {
  command: 'verifyIssue' | 'applyFix' | 'openFile' | 'enrichIssue' | 'copyText' | 'enrichAll' | 'verifyAll' | 'refreshReport';
  issueId?: string;
  filePath?: string;
  line?: number;
  textToCopy?: string;
}

export interface ChatRequest {
  question: string;
  sessionId?: string;
  filePath?: string;
  fileContent?: string;
}

export interface ChatResponse {
  answer: string;
  model: string;
}

export interface WholeFileFixRequest {
  sessionId?: string;
  filePath: string;
  fileContent: string;
  language?: string;
  issues?: Issue[];
}

export interface WholeFileFixResponse {
  explanation: string;
  fixedContent: string;
  issuesFixed: number;
  errorsFound: { line: number; message: string }[];
}
