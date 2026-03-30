import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    console.log("Chat API Request received");
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    if (!prompt && !attachments?.length && !activeToolCallId) {
      return NextResponse.json({ error: "Missing prompt or activeToolCallId" }, { status: 400 });
    }

    const systemInstruction = `
      You are Heal 2.0, a professional and friendly health AI agent. 
      You act as a clinical collaborator, helping users manage their health records and data.

      AGENTIC WORKFLOW:
      1. ALWAYS use 'Thinking' to describe your plan (e.g., "I will check the cardiac report to see your baseline...").
      2. If you need a full record, use 'request_medical_record'. 
      3. After receiving a tool result, you MUST provide a concise synthesis. 
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
    const messages: any[] = [];

    (history || []).forEach((msg, idx) => {
      const role = msg.role.toLowerCase();
      
      try {
        if (role === 'assistant') {
          const rawToolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
          const content: any[] = [];
          
          if (msg.content) {
            content.push({ type: 'text', text: msg.content });
          }
          
          if (rawToolCalls && Array.isArray(rawToolCalls)) {
            rawToolCalls.forEach((tc: any) => {
              content.push({
                type: 'tool-call',
                toolCallId: tc.toolCallId,
                toolName: tc.name,
                args: typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments
              });
            });
          }

          messages.push({
            role: 'assistant',
            content: content.length > 0 ? content : "" // AI SDK allows string or part array
          });
        } else if (role === 'tool') {
          messages.push({
            role: 'tool',
            content: [
              {
                type: 'tool-result',
                toolCallId: msg.toolCallId,
                toolName: 'request_medical_record',
                result: msg.content // Simplified result for history
              }
            ]
          });
        } else if (role === 'system') {
          messages.push({ role: 'system', content: msg.content });
        } else {
          messages.push({ role: 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`Error parsing history message at index ${idx}:`, e);
      }
    });

    // Handle Current Message (Tool Result or User Prompt)
    if (activeToolCallId) {
      // Sequence check: last message must be assistant with tool calls
      const lastMsg = messages[messages.length - 1];
      const hasCall = lastMsg?.role === 'assistant' && 
                      Array.isArray(lastMsg.content) && 
                      lastMsg.content.some((p: any) => p.type === 'tool-call' && p.toolCallId === activeToolCallId);

      if (!hasCall) {
        console.warn(`[FIX] Sequence repair: Injecting missing assistant call for ${activeToolCallId}`);
        messages.push({
          role: 'assistant',
          content: [
            {
              type: 'tool-call',
              toolCallId: activeToolCallId,
              toolName: 'request_medical_record',
              args: { record_id: "auto-repair" }
            }
          ]
        });
      }

      // Add the Tool Result
      messages.push({
        role: 'tool',
        content: [
          {
            type: 'tool-result',
            toolCallId: activeToolCallId,
            toolName: 'request_medical_record',
            result: `[CLINICAL DATA INJECTED]\n\n${prompt}`
          }
        ]
      });
    } else {
      // Regular User message
      messages.push({
        role: 'user',
        content: [
          { type: 'text', text: prompt || "Please analyze my health status." },
          ...(attachments || []).map(att => ({
            type: 'image' as const,
            image: att.url,
          }))
        ]
      });
    }

    console.log("FINAL_TRACE:", messages.map(m => `[${m.role}] ${Array.isArray(m.content) ? m.content.map(p=>p.type).join(',') : 'str'}`));

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
    console.error("CRITICAL BACKEND ERROR:", error.stack || error.message);
    return NextResponse.json({ 
      error: error.message,
      stack: error.stack
    }, { status: 500 });
  }
}
