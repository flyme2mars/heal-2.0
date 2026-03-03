export interface HealthConnectData {
  [key: string]: string;
}

export interface MemorySnapshot {
  [filename: string]: string;
}

export interface ChatRequest {
  prompt: string;
  context: {
    health_connect?: HealthConnectData;
    fhir_records?: string[];
    memory_snapshot: MemorySnapshot;
  };
}

export interface ChatResponse {
  text: string;
  updated_memory?: MemorySnapshot;
}
