import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText, tool, ModelMessage } from "ai";
import { z } from "zod";
import { ChatRequest } from "@/types/chat";

export const maxDuration = 60;

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.text();
    console.log("PAYLOAD_SIZE:", rawBody.length);
    const body: ChatRequest = JSON.parse(rawBody);
    const { prompt, history, context, attachments, toolCallId: activeToolCallId } = body;

    const healthSummary = context.health_connect?.summary || "No wearable vitals available.";
    const medicalSummary = context.fhir_records?.find(r => !r.startsWith("LOCAL VAULT INDEX:")) || "No FHIR summary available.";
    const vaultIndex = context.fhir_records?.find(r => r.startsWith("LOCAL VAULT INDEX:")) || "No records in vault.";

    const systemInstruction = `
      You are Heal 2.0, a clinical AI agent specializing in medical record synthesis and wearable data analysis.
      
      CURRENT USER DATA (Already Provided - DO NOT ASK FOR PERMISSION TO SEE THESE):
      - VITALS/WEARABLES: ${healthSummary}
      - MEDICAL SUMMARY: ${medicalSummary}
      
      VAULT INDEX (Request permission only if you need full details of a specific record listed here):
      ${vaultIndex}

      CRITICAL GUIDELINES:
      1. VITALS & SUMMARY: Use the vitals and medical summary provided above to answer health questions immediately. They are already authorized.
      2. RECORD ACCESS: Use 'request_medical_record' ONLY if you need to read the full text of a specific document listed in the VAULT INDEX that is directly relevant to the user's query.
      3. NON-EXISTENT RECORDS: If the user asks about a condition (e.g., headache) and no related record exists in the VAULT INDEX, state that you don't see any specific records for that in the vault, but analyze their provided vitals if helpful.
      4. DO NOT loop permissions for data you already have.
    `;

    const messages: ModelMessage[] = [];

    // Map history
    (history || []).forEach((msg, idx) => {
      const role = msg.role.toLowerCase();
      try {
        if (role === 'assistant') {
          const rawToolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
          
          if (rawToolCalls && Array.isArray(rawToolCalls) && rawToolCalls.length > 0) {
            const parts: any[] = [];
            // In 6.0, we must avoid empty text parts if tool calls exist
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
                result: msg.content // Fallback check: AI SDK 6.0 might prefer 'result' vs 'output' depending on sub-version
              }
            ]
          } as any);
        } else if (role === 'system') {
          messages.push({ role: 'system', content: msg.content });
        } else {
          messages.push({ role: 'user', content: msg.content });
        }
      } catch (e) {
        console.error(`History error at ${idx}:`, e);
      }
    });

    // Handle Current Turn
    if (activeToolCallId) {
      // Find the signature from the history
      const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');
      const signature = (lastAssistant as any)?.providerMetadata?.google?.thoughtSignature;

      messages.push({
        role: 'tool',
        content: [
          {
            type: 'tool-result',
            toolCallId: activeToolCallId,
            toolName: 'request_medical_record',
            result: `[AUTHORIZED RECORD CONTENT]:\n\n${prompt}`
          }
        ]
      } as any);

      messages.push({
        role: 'user',
        content: "I have provided the record. Please analyze it and provide your clinical synthesis."
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

    // Sequence Repair: Ensure tool results follow tool calls
    const repairedMessages: ModelMessage[] = [];
    messages.forEach((m, i) => {
      if (m.role === 'tool' && repairedMessages[repairedMessages.length - 1]?.role !== 'assistant') {
        // Inject a dummy assistant call if missing (Gemini safety)
        console.warn("Sequence Repair: Tool result without assistant call detected.");
      }
      repairedMessages.push(m);
    });

    const result = streamText({
      model: google("gemini-3.1-flash-lite-preview"),
      system: systemInstruction,
      messages: repairedMessages,
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
          description: "Get the full text of a specific medical record from the vault by its ID.",
          parameters: z.object({ 
            record_id: z.string().describe("The ID of the record from the VAULT INDEX."), 
            reason: z.string().describe("Clinical reason for accessing this specific record.") 
          })
        })
      }
    });

    return (result as any).toUIMessageStreamResponse();

  } catch (error: any) {
    console.error("PRE-FLIGHT ERROR:", error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
