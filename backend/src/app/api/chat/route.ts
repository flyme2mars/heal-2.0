import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    console.log("Chat API Request received");
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments } = body;

    if (!prompt && !attachments?.length) {
      return NextResponse.json({ error: "Missing prompt or attachment" }, { status: 400 });
    }

    const isSystemInjection = prompt?.startsWith("[SYSTEM_INJECTION:");

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI agent. 
      You act as a clinical collaborator, helping users manage their health records and data.

      AGENTIC WORKFLOW:
      1. ALWAYS use 'Thinking' to describe your plan (e.g., "I will check the cardiac report to see your baseline...").
      2. If you need a full record, use 'request_medical_record'. 
      3. After receiving a tool result (SYSTEM MEMORY UPDATE), you MUST provide a concise synthesis. 
      4. NEVER output an empty response after a tool result. 

      CURRENT LOCAL VAULT INDEX:
      ${context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault."}

      TONE GUIDELINES:
      - Be clinical, empathetic, and direct.
      - No fluff. No flowery greetings after the first turn.
      
      CONTEXT:
      - Health Vitals: ${context.health_connect ? JSON.stringify(context.health_connect) : 'No data available'}
      - FHIR Records: ${context.fhir_records?.filter(r => !r.startsWith("LOCAL VAULT INDEX:")).join('\n') || 'No clinical records on file.'}
      - Internal Memory: ${Object.entries(context.memory_snapshot).map(([file, content]) => `File ${file}: ${content}`).join('\n')}
    `;

    // 2026 Context Engineering: Convert history to AI SDK messages
    const messages: any[] = (history || []).map((msg, idx) => {
      const role = msg.role.toLowerCase();
      console.log(`HISTORY[${idx}]: role=${role}, hasToolCalls=${!!msg.toolCalls}, toolCallId=${msg.toolCallId}`);
      
      if (role === 'assistant') {
        const toolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
        return {
          role: 'assistant' as const,
          content: msg.content || "", 
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
              toolName: 'request_medical_record',
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

    // Handle Current Message
    const activeToolCallId = (body as any).toolCallId;
    if (activeToolCallId) {
      console.log(`>>> ATTACHING TOOL RESULT: ${activeToolCallId}`);
      messages.push({
        role: "tool" as const,
        content: [
          {
            type: "tool-result",
            toolCallId: activeToolCallId,
            toolName: "request_medical_record",
            result: `[SYSTEM MEMORY UPDATE: Full text of requested clinical record follows]\n\n${prompt}`
          }
        ]
      });
      
      // Force synthesis after tool result by appending an explicit instruction
      messages.push({
        role: "user" as const,
        content: "Please synthesize the information from the record I just provided and answer my previous question."
      });
    } else {
      messages.push({
        role: "user" as const,
        content: [
          { type: "text", text: isSystemInjection ? `SYSTEM MEMORY UPDATE: ${prompt}` : prompt || "Please analyze my health status." },
          ...(attachments || []).map(att => ({
            type: "image" as const,
            image: att.url,
          }))
        ]
      });
    }

    // Log the message sequence for debugging
    console.log("AGENT_SEQUENCE:", messages.map(m => `[${m.role}] ${Array.isArray(m.content) ? 'PartArray' : m.content?.slice(0, 20)}...`));

    // Use specific content parts for multimodal support
    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      messages: messages,
      providerOptions: {
        google: {
          thinkingConfig: {
            thinkingLevel: "medium",
            includeThoughts: true
          }
        }
      },
      tools: {
        update_memory: tool({
          description: "Update a memory file stored on the user's phone.",
          inputSchema: z.object({
            filename: z.string().describe("e.g., 'goals.md'"),
            content: z.string().describe("new markdown content")
          }),
        }),
        request_medical_record: tool({
          description: "Request full-text access to a specific medical record from the user's vault.",
          inputSchema: z.object({
            record_id: z.string().describe("The ID of the record to request"),
            reason: z.string().describe("A brief explanation for the user of why this record is needed.")
          }),
        })
      }
    });

    return (result as any).toDataStreamResponse({
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
