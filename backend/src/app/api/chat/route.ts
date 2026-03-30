import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, ModelMessage } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export const maxDuration = 60;

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.text();
    const body: ChatRequest = JSON.parse(rawBody);
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `
      You are Heal 2.0, a clinical AI agent. 
      INDEX: ${vaultIndex}
      AGENTIC WORKFLOW:
      1. Use 'request_medical_record' for data access.
      2. Synthesize results immediately.
    `;

    const messages: ModelMessage[] = [];

    // Map history with DEFINITIVE 6.0 schema
    (history || []).forEach((msg, idx) => {
      const role = msg.role.toLowerCase();
      try {
        if (role === 'assistant') {
          const rawToolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
          
          if (rawToolCalls && Array.isArray(rawToolCalls) && rawToolCalls.length > 0) {
            const parts: any[] = [];
            if (msg.content && msg.content.trim().length > 0) {
              parts.push({ type: 'text', text: msg.content });
            }
            
            rawToolCalls.forEach((tc: any) => {
              parts.push({
                type: 'tool-call',
                toolCallId: tc.toolCallId,
                toolName: tc.name,
                input: typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments
              });
            });
            
            const firstCall = rawToolCalls[0];
            const signature = firstCall?.thoughtSignature;

            messages.push({ 
              role: 'assistant', 
              content: parts,
              ...(signature ? { providerMetadata: { google: { thoughtSignature: signature } } } : {})
            } as any);
          } else {
            messages.push({ role: 'assistant', content: msg.content || " " });
          }
        } else if (role === 'tool') {
          messages.push({
            role: 'tool',
            content: [
              {
                type: 'tool-result',
                toolCallId: msg.toolCallId || "unknown",
                toolName: 'request_medical_record',
                output: { type: 'text', value: msg.content } // Correct 6.0 schema
              }
            ]
          });
        } else if (role === 'system') {
          messages.push({ role: 'system', content: msg.content });
        } else {
          messages.push({ role: 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`History error at ${idx}:`, e);
      }
    });

    // Handle Current
    if (activeToolCallId) {
      // Find the signature from the history to match the current tool-result
      const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');
      const signature = (lastAssistant as any)?.providerMetadata?.google?.thoughtSignature;

      messages.push({
        role: 'tool',
        content: [
          {
            type: 'tool-result',
            toolCallId: activeToolCallId,
            toolName: 'request_medical_record',
            output: { type: 'text', value: `[AUTHORIZED]:\n\n${prompt}` }
          }
        ]
      });

      messages.push({
        role: 'user',
        content: "Please analyze the record AUTHORIZED above and answer my question."
      });
    } else {
      if (attachments && attachments.length > 0) {
        messages.push({
          role: 'user',
          content: [
            { type: 'text', text: prompt || "Analyze my status." },
            ...attachments.map(a => ({ type: 'image' as const, image: a.url }))
          ]
        });
      } else {
        messages.push({ role: 'user', content: prompt || "Analyze my status." });
      }
    }

    console.log("FINAL_TRACE:", JSON.stringify(messages.map(m => ({ 
      role: m.role, 
      parts: Array.isArray(m.content) ? m.content.map(p => p.type) : 'str' 
    })), null, 2));

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
          description: "Get medical record",
          inputSchema: z.object({ record_id: z.string(), reason: z.string() })
        })
      }
    });

    return (result as any).toUIMessageStreamResponse();

  } catch (error: any) {
    console.error("AGENT ERROR:", error.message);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
