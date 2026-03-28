import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    console.log("Chat API Request Received (Model: 3.1 Flash-Lite)");

    const body: ChatRequest = await req.json();
    const { prompt, context } = body;

    if (!prompt) return NextResponse.json({ error: "Missing prompt" }, { status: 400 });

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI assistant. 
      Your goal is to help the user understand their health data and medical records.

      TONE GUIDELINES:
      1. Be professional, calm, and empathetic. 
      2. Use clear, direct language. Avoid medical jargon unless you explain it simply.
      3. ABSOLUTELY NO flowery, "magical," or overly enthusiastic language (e.g., avoid "seeker," "journey," "shimmering," "mystical").
      4. Do not use excessive emojis. One or two for friendliness is fine.
      5. Be concise. Get to the point quickly.

      CAPABILITIES:
      - You have access to the user's vitals (Steps, SpO2, Calories) and a list of medical documents in their vault.
      - If you need more detail from a specific document summary to give a better answer, use the 'read_medical_record' tool.
      - If the user provides important personal health facts or goals, use 'update_memory' to save them.

      CONTEXT:
      - Health Vitals: ${context.health_connect ? Object.entries(context.health_connect).map(([k, v]) => `${k}: ${v}`).join(', ') : 'No data available'}
      - Medical Summary: ${context.fhir_records?.join('\n') || 'No clinical records on file.'}
      - Internal Memory: ${Object.entries(context.memory_snapshot).map(([file, content]) => `File ${file}: ${content}`).join('\n')}
    `;

    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      prompt: prompt,
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
    return NextResponse.json({ 
      error: error.message
    }, { status: 500 });
  }
}
