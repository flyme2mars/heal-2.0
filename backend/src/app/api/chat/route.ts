import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, generateText } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  let lastAttemptedMessages: any[] = [];
  try {
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `
      You are Heal 2.0, a highly capable clinical health AI.
      YOU HAVE ACCESS TO A VAULT INDEX. NEVER ASK THE USER FOR RECORD IDS.
      IDs are in the index below.

      CURRENT LOCAL VAULT INDEX:
      ${vaultIndex}

      AGENTIC WORKFLOW:
      1. Use 'Thinking' to describe your plan.
      2. If you need a full record, use 'request_medical_record'. 
      3. After receiving data, provide a synthesis and answer the user's question.

      CONTEXT:
      - Vitals: ${context.health_connect ? JSON.stringify(context.health_connect) : 'None'}
      - Medical Summary: ${context.fhir_records?.filter(r => !r.startsWith("LOCAL VAULT INDEX:")).join('\n') || 'None'}
    `;

    const messages: any[] = [];

    // Map history
    (history || []).forEach((msg, idx) => {
      const role = msg.role.toLowerCase();
      try {
        if (role === 'assistant') {
          const rawToolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
          if (rawToolCalls && Array.isArray(rawToolCalls) && rawToolCalls.length > 0) {
            const parts: any[] = [];
            if (msg.content) parts.push({ type: 'text', text: msg.content });
            rawToolCalls.forEach((tc: any) => {
              parts.push({
                type: 'tool-call',
                toolCallId: tc.toolCallId,
                toolName: tc.name,
                args: typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments
              });
            });
            messages.push({ role: 'assistant', content: parts });
          } else {
            messages.push({ role: 'assistant', content: msg.content || "" });
          }
        } else if (role === 'tool') {
          messages.push({
            role: 'tool',
            content: [
              {
                type: 'tool-result',
                toolCallId: msg.toolCallId || "missing-id",
                toolName: 'request_medical_record',
                result: msg.content 
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

    // Handle Current
    if (activeToolCallId) {
      // Manual sequence repair if missing
      const lastMsg = messages[messages.length - 1];
      const hasCall = lastMsg?.role === 'assistant' && 
                      Array.isArray(lastMsg.content) && 
                      lastMsg.content.some((p: any) => p.type === 'tool-call' && p.toolCallId === activeToolCallId);

      if (!hasCall) {
        messages.push({
          role: 'assistant',
          content: [{ type: 'tool-call', toolCallId: activeToolCallId, toolName: 'request_medical_record', args: { record_id: "auto-repair" } }]
        });
      }

      messages.push({
        role: "tool",
        content: [{ type: "tool-result", toolCallId: activeToolCallId, toolName: "request_medical_record", result: `[CLINICAL DATA INJECTED]\n\n${prompt}` }]
      });
      
      messages.push({
        role: "user",
        content: "Please synthesize the information from the record I just provided and answer my previous question."
      });
    } else {
      if (attachments && attachments.length > 0) {
        messages.push({
          role: 'user',
          content: [
            { type: 'text', text: prompt || "Please analyze my health status." },
            ...attachments.map(att => ({ type: 'image' as const, image: att.url }))
          ]
        });
      } else {
        messages.push({ role: 'user', content: prompt || "Please analyze my health status." });
      }
    }

    lastAttemptedMessages = messages;

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
        request_medical_record: tool({
          description: "Request full-text access to a specific medical record from the user's vault using its ID from the index.",
          inputSchema: z.object({
            record_id: z.string().describe("The ID from the LOCAL VAULT INDEX"),
            reason: z.string().describe("Why you need this record")
          }),
        })
      }
    });

    return (result as any).toUIMessageStreamResponse();

  } catch (error: any) {
    console.error("CRITICAL BACKEND ERROR:", error.message);
    return NextResponse.json({ 
      error: error.message,
      debug_sequence: lastAttemptedMessages.map(m => m.role)
    }, { status: 500 });
  }
}
