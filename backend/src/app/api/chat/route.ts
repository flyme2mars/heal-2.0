import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    console.log("Chat API Request Received (Model: 3.1 Flash-Lite, Multimodal)");

    const body: ChatRequest = await req.json();
    const { prompt, context, attachments } = body;

    if (!prompt) return NextResponse.json({ error: "Missing prompt" }, { status: 400 });

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI assistant. 
      Your goal is to help the user understand their health data, medical records, and any images they provide.

      TONE GUIDELINES:
      1. Be professional, calm, and empathetic. 
      2. Use clear, direct language. Avoid medical jargon unless you explain it simply.
      3. ABSOLUTELY NO flowery or "magical" language.
      4. Be concise.

      VISION GUIDELINES:
      - If the user provides an image, analyze it carefully. 
      - If it is a medical report, extract the key findings.
      - If it is a photo of a symptom (like a rash), describe what you see neutrally and recommend appropriate steps (e.g., "This appears to be X, you should consult a dermatologist").
      - Always state that you are an AI and your analysis is for informational purposes only.

      CONTEXT:
      - Health Vitals: ${context.health_connect ? Object.entries(context.health_connect).map(([k, v]) => `${k}: ${v}`).join(', ') : 'No data available'}
      - Medical Summary: ${context.fhir_records?.join('\n') || 'No clinical records on file.'}
      - Internal Memory: ${Object.entries(context.memory_snapshot).map(([file, content]) => `File ${file}: ${content}`).join('\n')}
    `;

    // Convert attachments to AI SDK format
    const experimental_attachments = attachments?.map(att => ({
      url: att.url,
      contentType: "image/jpeg", // Assuming JPEG for now
    }));

    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      prompt: prompt,
      experimental_attachments,
      tools: {
        update_memory: {
          description: "Update a memory file stored on the user's phone.",
          parameters: z.object({
            filename: z.string().describe("e.g., 'goals.md'"),
            content: z.string().describe("new markdown content")
          }),
        } as any,
        read_medical_record: {
          description: "Request the full text content of a specific medical document from the user's vault.",
          parameters: z.object({
            filename: z.string().describe("The name of the file to read")
          }),
        } as any
      }
    });

    const stream = new ReadableStream({
      async start(controller) {
        const encoder = new TextEncoder();
        try {
          for await (const part of result.fullStream) {
            if (part.type === 'text-delta') {
              controller.enqueue(encoder.encode(`data: 0:${JSON.stringify(part.text)}\n\n`));
            } else if (part.type === 'tool-call') {
              console.log("AI requested tool:", part.toolName);
              controller.enqueue(encoder.encode(`data: 9:${JSON.stringify({
                toolCallId: part.toolCallId,
                toolName: part.toolName,
                args: part.input
              })}\n\n`));
            }
          }
          controller.close();
        } catch (e) {
          console.error("Stream error:", e);
          controller.error(e);
        }
      }
    });

    return new Response(stream, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
      }
    });
  } catch (error: any) {
    console.error("PRODUCTION ERROR:", error.stack || error.message);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
