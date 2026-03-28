import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool } from "ai";
import { z } from "zod";
import { verifyAppCheck } from "@/lib/firebase";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    const body: ChatRequest = await req.json();
    const { prompt, context } = body;

    if (!prompt) return NextResponse.json({ error: "Missing prompt" }, { status: 400 });

    const systemInstruction = `
      You are Heal 2.0, a secure health AI agent.
      
      HEALTH CONTEXT:
      ${context.health_connect ? Object.entries(context.health_connect).map(([k, v]) => `- ${k}: ${v}`).join('\n') : 'No data'}
      
      MEDICAL HISTORY:
      ${context.fhir_records?.join('\n') || 'No records found.'}
      
      MEMORY:
      ${Object.entries(context.memory_snapshot).map(([file, content]) => `File: ${file}\n${content}`).join('\n---\n')}
      
      INSTRUCTIONS:
      1. Use 'update_memory' if the user tells you something important.
      2. Keep responses professional.
    `;

    console.log("Starting Agentic stream with model: gemini-2.5-flash");

    const result = streamText({
      model: google("gemini-2.5-flash"),
      system: systemInstruction,
      prompt: prompt,
      tools: {
        update_memory: tool({
          description: "Update a memory file stored on the user's phone.",
          parameters: z.object({
            filename: z.string().describe("e.g., 'soul.md'"),
            content: z.string().describe("new markdown content")
          }),
          execute: async () => ({ status: "pending_client_execution" })
        })
      }
    });

    const stream = new ReadableStream({
      async start(controller) {
        const encoder = new TextEncoder();
        try {
          // Use textStream for reliable token delivery
          for await (const chunk of result.textStream) {
            console.log("Chunk:", chunk);
            const sseChunk = `data: 0:${JSON.stringify(chunk)}\n\n`;
            controller.enqueue(encoder.encode(sseChunk));
          }
          
          // Check if there were tool calls
          const toolCalls = await result.toolCalls;
          for (const toolCall of toolCalls) {
            if (toolCall.toolName === 'update_memory') {
              console.log("AI called update_memory");
              const toolSse = `data: 9:${JSON.stringify({
                toolCallId: toolCall.toolCallId,
                toolName: toolCall.toolName,
                args: toolCall.args
              })}\n\n`;
              controller.enqueue(encoder.encode(toolSse));
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
    console.error("Chat API error:", error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
