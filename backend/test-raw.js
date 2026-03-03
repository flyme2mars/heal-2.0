async function testRaw() {
  const payload = {
    prompt: "Hello",
    context: { memory_snapshot: {} }
  };

  const response = await fetch('http://127.0.0.1:3000/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  console.log('Status:', response.status);
  const text = await response.text();
  console.log('Raw Response:', text);
}

testRaw();
