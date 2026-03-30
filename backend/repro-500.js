async function repro() {
  const payload = {
    "prompt": "--- Page 1 ---\nKERALA INSTITUTE OF CARDIAC SCIENCES...",
    "history": [
      { "role": "user", "content": "what's the reason for my chest tightness " },
      { 
        "role": "assistant", 
        "content": "",
        "toolCalls": JSON.stringify([{
          "toolCallId": "Agcn7wyp8dsLj83g",
          "name": "request_medical_record",
          "arguments": "{\"record_id\":\"898024c3-b1af-4ed9-893a-b1ead88e2cff\"}"
        }])
      }
    ],
    "context": {
      "health_connect": { "summary": "User Health Data Summary..." },
      "fhir_records": ["LOCAL VAULT INDEX..."],
      "memory_snapshot": {}
    },
    "toolCallId": "Agcn7wyp8dsLj83g"
  };

  const res = await fetch('https://heal-eight.vercel.app/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });

  console.log("Status:", res.status);
  const data = await res.json();
  console.log("Response:", JSON.stringify(data, null, 2));
}

repro();
