import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  let lastAttemptedMessages: any[] = [];
  try {
    console.log(">>> [DEBUG] NEW REQUEST START");
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `
      You are Heal 2.0, a professional and highly capable health AI agent. 
      You act as a clinical collaborator, helping users manage their health records and data.

      CRITICAL AGENTIC BEHAVIOR:
      1. YOU HAVE ACCESS TO A VAULT INDEX. Look at the "CURRENT LOCAL VAULT INDEX" below.
      2. NEVER ASK THE USER FOR RECORD IDS. The IDs are already provided in the index.
      3. IF YOU SEE A RELEVANT RECORD, CALL 'request_medical_record' IMMEDIATELY using the ID from the index.
      4. ALWAYS use 'Thinking' to describe your clinical reasoning process.
      5. After receiving clinical data (tool result), provide a thorough synthesis and answer the user's original question.

      CURRENT LOCAL VAULT INDEX:
      ${vaultIndex}

      TONE & STYLE:
      - Clinical, direct, and empathetic.
      - No unnecessary greetings. 
      - If you are calling a tool, explain briefly in 'Thinking' and then execute.

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
        } else {
          messages.push({ role: role === 'system' ? 'system' : 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`>>> [DEBUG] History Parse Error at index ${idx}:`, e);
      }
    });

    // Handle Current
    if (activeToolCallId) {
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
        content: "I have granted you access to the record. Please synthesize the data and answer my previous question about my chest tightness."
      });
    } else {
      if (attachments?.length) {
        messages.push({
          role: "user",
          content: [
            { type: "text", text: prompt || "" },
            ...attachments.map(a => ({ type: "image", image: a.url }))
          ]
        });
      } else {
        messages.push({ role: "user", content: prompt || "" });
      }
    }

    lastAttemptedMessages = messages;
    console.log("FINAL_AGENT_SEQUENCE:", messages.map(m => `[${m.role}]`));

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
    console.error(">>> [CRITICAL] 500 ERROR DETECTED:", error.message);
    return NextResponse.json({ 
      error: error.message,
      debug_sequence: lastAttemptedMessages.map(m => m.role)
    }, { status: 500 });
  }
}
