const axios = require('axios');

async function testToolResult() {
  console.log("--- Testing Agentic Flow (Tool Result Continuation) ---");
  
  const payload = {
    prompt: "Patient has chest tightness. I need to review the cardiology report.",
    history: [
      { role: "user", content: "Why do I have chest tightness?" },
      { 
        role: "assistant", 
        content: "I need to access your cardiology record to help you.",
        toolCalls: JSON.stringify([{
          toolCallId: "call_123",
          name: "request_medical_record",
          arguments: '{"id": "37e9a966-002e-4808-a318-28673a9dd3", "reason": "Analyze cardiac history"}'
        }])
      }
    ],
    context: {
      health_connect: { summary: "Heart rate normal." },
      fhir_records: ["LOCAL VAULT INDEX: DOCUMENT [ID: 37e9a966-002e-4808-a318-28673a9dd3] | Label: Cardio Report"],
      memory_snapshot: {}
    },
    toolCallId: "call_123" // Simulating the approval follow-up
  };

  try {
    const response = await axios.post('http://localhost:3000/api/chat', payload, {
      responseType: 'stream'
    });

    console.log("Response Status:", response.status);
    
    response.data.on('data', (chunk) => {
      const line = chunk.toString();
      console.log("Chunk received:", line);
    });

    response.data.on('end', () => {
      console.log("Stream finished.");
    });

  } catch (error) {
    console.error("Error during test:", error.response?.data || error.message);
  }
}

testToolResult();
