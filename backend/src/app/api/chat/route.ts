import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText } from "ai";
import { verifyAppCheck } from "@/lib/firebase";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    const { prompt, context } = await req.json();
    if (!prompt) return NextResponse.json({ error: "No prompt" }, { status: 400 });

    const systemInstruction = `
      You are Remini, a secure health AI agent.
      
      HEALTH CONTEXT:
      ${context.health_connect ? Object.entries(context.health_connect).map(([k, v]) => `- ${k}: ${v}`).join('\n') : 'No data'}
      
      MEDICAL HISTORY:
      ${context.fhir_records?.join('\n') || 'No records found.'}
      
      MEMORY (Markdown files):
      ${Object.entries(context.memory_snapshot).map(([file, content]) => `File: ${file}\n${content}`).join('\n---\n')}
      
      INSTRUCTIONS:
      1. Answer the user's health questions using the provided context.
      2. Maintain a professional yet magical health companion persona.
    `;

    console.log("Starting AI stream with model: gemini-2.5-flash");

    const result = streamText({
      model: google("gemini-2.5-flash"),
      system: systemInstruction,
      prompt: prompt,
    });

    const stream = new ReadableStream({
      async start(controller) {
        try {
          for await (const chunk of result.textStream) {
            // Format as Vercel-compatible SSE: data: 0:"token"\n\n
            const sseChunk = `data: 0:${JSON.stringify(chunk)}\n\n`;
            controller.enqueue(new TextEncoder().encode(sseChunk));
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
    console.error("Chat API error:", error);
    return NextResponse.json(
      { error: error.message || "Internal Server Error" },
      { status: error.status || 500 }
    );
  }
}
