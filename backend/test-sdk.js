const { createGoogleGenerativeAI } = require('@ai-sdk/google');
const { generateText } = require('ai');

async function testSDK() {
  console.log("Starting SDK test...");
  const google = createGoogleGenerativeAI({
    apiKey: process.env.GOOGLE_GENERATIVE_AI_API_KEY,
  });

  try {
    const { text } = await generateText({
      model: google('gemini-1.5-flash'),
      prompt: 'Write a 3 word greeting.',
    });
    console.log('Response:', text);
  } catch (error) {
    console.error('SDK Error:', error);
  }
}

testSDK();
