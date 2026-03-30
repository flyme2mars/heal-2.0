import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, ModelMessage } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export const maxDuration = 60;

export async function POST(req: NextRequest) {
  let lastAttemptedMessages: any[] = [];
  try {
    const rawBody = await req.text();
    const body: ChatRequest = JSON.parse(rawBody);
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `You are Heal 2.0. Clinical AI. Vault Index: ${vaultIndex}`;

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
                // Check if version 6.0 needs 'args' or 'input'
                args: typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments
              });
            });
            messages.push({ role: 'assistant', content: parts });
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
                result: msg.content // Trying 'result' again as some 6.0 versions expect this
              }
            ]
          });
        } else {
          messages.push({ role: role === 'system' ? 'system' : 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`History error at ${idx}:`, e);
      }
    });

    // Handle Current
    if (activeToolCallId) {
      // Ensure matching call exists in messages
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
            args: { record_id: "repair" } 
          }]
        });
      }

      messages.push({
        role: 'tool',
        content: [{
          type: 'tool-result',
          toolCallId: activeToolCallId,
          toolName: 'request_medical_record',
          result: `[DATA]: ${prompt}`
        }]
      });
    } else {
      messages.push({
        role: 'user',
        content: prompt || "Analyze status."
      });
    }

    lastAttemptedMessages = messages;

    // THE ACTUAL INVESTIGATION: 
    // We will call streamText but catch the specific error to find the missing field.
    try {
      const result = streamText({
        model: google("gemini-3.1-flash-lite-preview"),
        system: systemInstruction,
        messages: messages,
        tools: {
          request_medical_record: tool({
            description: "Get record",
            inputSchema: z.object({ record_id: z.string(), reason: z.string() })
          })
        }
      });

      return (result as any).toUIMessageStreamResponse();
    } catch (streamError: any) {
      console.error(">>> [INVESTIGATION] STREAM_TEXT REJECTED PROMPT");
      console.error(">>> ERROR:", streamError.message);
      // Return the error details to Android so we can read it in the logs
      return NextResponse.json({ 
        error: "INVESTIGATION_FAILED", 
        detail: streamError.message,
        messages_sent: messages.map(m => ({ role: m.role, contentIsArray: Array.isArray(m.content) }))
      }, { status: 400 });
    }

  } catch (error: any) {
    console.error("CRITICAL ERROR:", error.message);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
