import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    console.log("Chat API Request Received (Model: 3.1 Flash-Lite, Multimodal Array)");

    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments } = body;

    if (!prompt && !attachments?.length) {
      return NextResponse.json({ error: "Missing prompt or attachment" }, { status: 400 });
    }

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI agent. 
      You act as a clinical collaborator, helping users manage their health records and data.

      IMPORTANT FLOW:
      1. You are provided with a "LOCAL VAULT INDEX" which contains IDs and summaries of the user's health records.
      2. If you need the full text of a specific record to answer accurately, you MUST use the 'request_medical_record' tool.
      3. Explain to the user WHY you need access to that specific record before or while calling the tool.
      4. Once approved, the system will provide the full text in a follow-up message.

      TONE GUIDELINES:
      1. Be professional, calm, and empathetic. 
      2. Use clear, direct language. Avoid medical jargon unless you explain it simply.
      3. ABSOLUTELY NO flowery or "magical" language.
      4. Be concise and actionable.

      CONTEXT:
      - Health Vitals: ${context.health_connect ? JSON.stringify(context.health_connect) : 'No data available'}
      - Medical Summary: ${context.fhir_records?.join('\n') || 'No clinical records on file.'}
      - Internal Memory: ${Object.entries(context.memory_snapshot).map(([file, content]) => `File ${file}: ${content}`).join('\n')}
    `;

    // 2026 Context Engineering: Convert history to AI SDK messages
    const pastMessages: any[] = (history || []).map(msg => ({
      role: msg.role === 'assistant' ? 'assistant' : 'user',
      content: msg.content
    }));

    // Add current prompt with attachments
    const currentMessage = {
      role: "user" as const,
      content: [
        { type: "text", text: prompt || "Please analyze my health status." },
        ...(attachments || []).map(att => ({
          type: "image" as const,
          image: att.url,
        }))
      ]
    };

    // Use specific content parts for multimodal support
    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      messages: [...pastMessages, currentMessage],
      providerOptions: {
        google: {
          thinkingConfig: {
            thinkingLevel: "medium",
            includeThoughts: true
          }
        }
      },
      tools: {
        update_memory: {
          description: "Update a memory file stored on the user's phone.",
          parameters: z.object({
            filename: z.string().describe("e.g., 'goals.md'"),
            content: z.string().describe("new markdown content")
          }),
        } as any,
        request_medical_record: {
          description: "Request full-text access to a specific medical record from the user's vault. Use this if the summary in the index is insufficient.",
          parameters: z.object({
            id: z.string().describe("The ID of the record to request"),
            reason: z.string().describe("A brief explanation for the user of why this record is needed.")
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
            } else if (part.type === 'reasoning-delta') {
              controller.enqueue(encoder.encode(`data: r:${JSON.stringify(part.text)}\n\n`));
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
