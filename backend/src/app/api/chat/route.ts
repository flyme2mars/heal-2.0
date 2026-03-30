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

    // Log input metadata
    console.log(`>>> [DEBUG] Prompt length: ${prompt?.length}, History size: ${history?.length}, ActiveToolCall: ${activeToolCallId}`);

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
          if (rawToolCalls && Array.isArray(rawToolCalls)) {
            messages.push({
              role: 'assistant',
              content: [
                { type: 'text', text: msg.content || "" },
                ...rawToolCalls.map((tc: any) => ({
                  type: 'tool-call',
                  toolCallId: tc.toolCallId,
                  toolName: tc.name,
                  args: typeof tc.arguments === 'string' ? JSON.parse(tc.arguments) : tc.arguments
                }))
              ]
            });
          } else {
            messages.push({ role: 'assistant', content: msg.content || "" });
          }
        } else if (role === 'tool') {
          messages.push({
            role: 'tool',
            content: [
              {
                type: 'tool-result',
                toolCallId: msg.toolCallId,
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
      messages.push({
        role: "tool",
        content: [
          {
            type: "tool-result",
            toolCallId: activeToolCallId,
            toolName: "request_medical_record",
            result: `[CLINICAL DATA]\n\n${prompt}`
          }
        ]
      });
      // Add follow-up user prompt to trigger synthesis
      messages.push({
        role: "user",
        content: "Please synthesize the record above."
      });
    } else {
      messages.push({
        role: "user",
        content: attachments?.length ? [
          { type: "text", text: prompt || "" },
          ...attachments.map(a => ({ type: "image", image: a.url }))
        ] : prompt || ""
      });
    }

    lastAttemptedMessages = messages;
    console.log(">>> [DEBUG] FINAL SEQUENCE:", JSON.stringify(messages.map(m => ({ role: m.role, contentType: typeof m.content })), null, 2));

    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      messages: messages,
      tools: {
        request_medical_record: tool({
          description: "Get medical record",
          inputSchema: z.object({ record_id: z.string(), reason: z.string() })
        })
      }
    });

    return (result as any).toDataStreamResponse();

  } catch (error: any) {
    console.error(">>> [CRITICAL] 500 ERROR DETECTED");
    console.error(">>> ERROR MESSAGE:", error.message);
    console.error(">>> ERROR STACK:", error.stack);
    console.error(">>> ATTEMPTED MESSAGES:", JSON.stringify(lastAttemptedMessages, null, 2));
    
    return NextResponse.json({ 
      error: error.message,
      debug_sequence: lastAttemptedMessages.map(m => m.role)
    }, { status: 500 });
  }
}
