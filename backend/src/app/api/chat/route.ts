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

    const isSystemInjection = prompt?.startsWith("[SYSTEM_INJECTION:");

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI agent. 
      You act as a clinical collaborator, helping users manage their health records and data.

      IMPORTANT FLOW (AGENTIC MEMORY):
      1. If you need a full record to answer, use the 'request_medical_record' tool.
      2. When the user approves, you will receive a message starting with '[SYSTEM_INJECTION]'.
      3. TREAT SYSTEM INJECTIONS AS PRIVATE WORKING MEMORY. 
      4. DO NOT repeat the full content of the injection back to the user.
      5. Summarize the findings and explain how it relates to their question.

      TONE GUIDELINES:
      - Be concise, actionable, and empathetic. 
      - Use 'Thinking' to show your research process.
      
      CONTEXT:
      - Health Vitals: ${context.health_connect ? JSON.stringify(context.health_connect) : 'No data available'}
      - Medical Summary: ${context.fhir_records?.join('\n') || 'No clinical records on file.'}
      - Internal Memory: ${Object.entries(context.memory_snapshot).map(([file, content]) => `File ${file}: ${content}`).join('\n')}
    `;

    // 2026 Context Engineering: Convert history to AI SDK messages
    const pastMessages: any[] = (history || []).map(msg => {
      const role = msg.role.toLowerCase();
      
      if (role === 'assistant') {
        const toolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
        return {
          role: 'assistant' as const,
          content: msg.content,
          toolCalls: toolCalls?.map((tc: any) => ({
            type: 'function',
            id: tc.toolCallId,
            function: {
              name: tc.name,
              arguments: tc.arguments
            }
          }))
        };
      }

      if (role === 'tool') {
        return {
          role: 'tool' as const,
          content: [
            {
              type: 'tool-result',
              toolCallId: msg.toolCallId,
              toolName: 'request_medical_record', // Default for this app
              result: msg.content
            }
          ]
        };
      }

      if (role === 'system') {
        return {
          role: 'system' as const,
          content: msg.content
        };
      }

      // Default to User
      return {
        role: 'user' as const,
        content: msg.content
      };
    });

    // If it's a system injection, we can optionally wrap it as a more authoritative system message
    // BUT if toolCallId is present, we should actually treat the current prompt as a tool result!
    let currentMessage: any;
    const activeToolCallId = (body as any).toolCallId;

    if (activeToolCallId) {
      currentMessage = {
        role: "tool" as const,
        content: [
          {
            type: "tool-result",
            toolCallId: activeToolCallId,
            toolName: "request_medical_record",
            result: prompt
          }
        ]
      };
    } else {
      currentMessage = {
        role: "user" as const,
        content: [
          { type: "text", text: isSystemInjection ? `SYSTEM MEMORY UPDATE: ${prompt}` : prompt || "Please analyze my health status." },
          ...(attachments || []).map(att => ({
            type: "image" as const,
            image: att.url,
          }))
        ]
      };
    }

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
