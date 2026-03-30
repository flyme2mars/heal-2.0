import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, createDataStreamResponse } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  let lastAttemptedMessages: any[] = [];
  try {
    console.log(">>> [DEBUG] NEW REQUEST START");
    const body: ChatRequest = await req.json();
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const systemInstruction = `
      You are Heal 2.0, a clinical health AI.
      AGENTIC WORKFLOW:
      1. ALWAYS use 'Thinking' to describe your plan.
      2. Use 'request_medical_record' for full records.
      3. synthesize data immediately after receiving it. 
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
      // Sequence check
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
        content: [{ type: "tool-result", toolCallId: activeToolCallId, toolName: "request_medical_record", result: `[CLINICAL DATA]\n\n${prompt}` }]
      });
      messages.push({ role: "user", content: "Please synthesize the record above." });
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

    return createDataStreamResponse({
      execute: (dataStream) => {
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

        result.mergeIntoDataStream(dataStream);
      },
      onError: (error) => {
        console.error("DATA_STREAM_ERROR:", error);
        return "Internal server error";
      }
    });

  } catch (error: any) {
    console.error(">>> [CRITICAL] 500 ERROR DETECTED:", error.message);
    return NextResponse.json({ 
      error: error.message,
      debug_sequence: lastAttemptedMessages.map(m => m.role)
    }, { status: 500 });
  }
}
