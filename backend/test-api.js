async function testChat() {
  const payload = {
    prompt: "Hello Remini, what do you know about me?",
    context: {
      health_connect: {
        summary: "User has taken 5000 steps today."
      },
      memory_snapshot: {
        "identity.md": "# User Profile\nName: John Doe\nAge: 29"
      },
      fhir_records: [
        "Patient John Doe, Weight 75kg"
      ]
    }
  };

  try {
    const response = await fetch('http://localhost:3000/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      console.error('Error:', response.status, await response.text());
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    console.log('Streaming response:');
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value);
      process.stdout.write(chunk);
    }
    console.log('\nStream finished.');
  } catch (error) {
    console.error('Fetch error:', error);
  }
}

testChat();
