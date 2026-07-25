export interface ApiError {
  code: string;
  category: string;
  stage: string;
  title: string;
  message: string;
  retryAfter?: number;
  codes?: string[];
  dates?: string[];
  fields?: string[];
}

export interface PeriodBlock {
  startDate: string;
  endDate: string;
  titleRow: number;
  headerRow: number;
  dataStartRow: number;
  dataEndRow: number;
}

export interface TemplateMetadata {
  sheetIndex: number;
  sheetName: string;
  periods: PeriodBlock[];
  latestPeriod: PeriodBlock;
}

export interface AnalyzeResponse {
  status: "success";
  operation: "analyze";
  file: { size: number; format: "xls" | "xlsx" };
  metadata: TemplateMetadata;
}

export interface TaskEvent {
  sequence: number;
  taskId: string;
  status: "running" | "success" | "error";
  stage: string;
  progress: number;
  message: string;
  error?: ApiError;
  result?: string;
  downloadUrl?: string;
  filename?: string;
}
