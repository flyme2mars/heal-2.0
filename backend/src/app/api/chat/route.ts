import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, ModelMessage } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export const maxDuration = 60;

export async function POST(req: NextRequest) {
  let lastAttemptedMessages: ModelMessage[] = [];
  try {
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `
      You are Heal 2.0, a professional clinical health AI.
      INDEX: ${vaultIndex}
      
      BEHAVIOR:
      - Use 'request_medical_record' for clinical data.
      - NEVER ask for IDs. 
      - Always provide synthesis after results.
    `;

    const messages: ModelMessage[] = [];

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
                toolCallId: msg.toolCallId || "unknown",
                toolName: 'request_medical_record',
                output: msg.content // Runtime fallback
              } as any
            ]
          });
        } else if (role === 'system') {
          messages.push({ role: 'system', content: msg.content });
        } else {
          messages.push({ role: 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`History error ${idx}:`, e);
      }
    });

    if (activeToolCallId) {
      const last = messages[messages.length - 1];
      const hasCall = last?.role === 'assistant' && 
                      Array.isArray(last.content) && 
                      last.content.some((p: any) => p.type === 'tool-call' && p.toolCallId === activeToolCallId);

      if (!hasCall) {
        messages.push({
          role: 'assistant',
          content: [{ 
            type: 'tool-call', 
            toolCallId: activeToolCallId, 
            toolName: 'request_medical_record', 
            args: { record_id: "auto-repaired" } 
          } as any]
        });
      }

      messages.push({
        role: 'tool',
        content: [{
          type: 'tool-result',
          toolCallId: activeToolCallId,
          toolName: 'request_medical_record',
          output: prompt 
        } as any]
      });

      messages.push({
        role: 'user',
        content: "AUTHORIZED. Analyze the record and explain my chest tightness."
      });
    } else {
      if (attachments && attachments.length > 0) {
        messages.push({
          role: 'user',
          content: [
            { type: 'text', text: prompt || "Analyze status." },
            ...attachments.map(a => ({ type: 'image' as const, image: a.url }))
          ]
        });
      } else {
        messages.push({ role: 'user', content: prompt || "Analyze status." });
      }
    }

    lastAttemptedMessages = messages;

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
          description: "Get medical record.",
          inputSchema: z.object({
            record_id: z.string(),
            reason: z.string()
          })
        })
      }
    });

    return (result as any).toUIMessageStreamResponse();

  } catch (error: any) {
    console.error("ERROR:", error.message);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
