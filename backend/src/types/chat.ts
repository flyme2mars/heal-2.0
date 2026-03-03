export interface HealthConnectData {
  steps?: number;
  avg_hr?: number;
  [key: string]: any;
}

export interface MedicalRecord {
  resourceType: string;
  id: string;
  [key: string]: any;
}

export interface MemorySnapshot {
  [filename: string]: string;
}

export interface ChatRequest {
  prompt: string;
  context: {
    health_connect?: HealthConnectData;
    fhir_records?: MedicalRecord[];
    memory_snapshot: MemorySnapshot;
  };
}

export interface ChatResponse {
  text: string;
  updated_memory?: MemorySnapshot;
}
