export interface ChatRequest {
  prompt: string;
  history?: { role: string; content: string }[];
  context: ChatContext;
  attachments?: ChatAttachment[];
}

export interface ChatAttachment {
  type: "image";
  url: string; // Base64 data URI for now
}

export interface ChatContext {
  health_connect: HealthConnectData;
  fhir_records: string[];
  memory_snapshot: Record<string, string>;
}

export interface HealthConnectData {
  summary: string;
}
