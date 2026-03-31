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
      You are Heal 2.0, a clinical AI agent. 
      
      USER DATA (ALREADY AUTHORIZED - USE THESE DIRECTLY):
      - VITALS/WEARABLES: ${healthSummary}
      - MEDICAL SUMMARY: ${medicalSummary}
      
      VAULT INDEX (List of records in the user's private vault):
      ${vaultIndex}

      DIAGNOSTIC GUIDELINES:
      1. USE PROVIDED DATA: For questions about vitals, wearables, or general medical history, use the 'USER DATA' section above. DO NOT use 'request_medical_record' for this data.
      2. RECORD ACCESS: Use 'request_medical_record' ONLY if you need the full text of a specific document listed in the 'VAULT INDEX' that is highly relevant to the user's query.
      3. MISSING RECORDS: If the user asks about a condition (e.g., headache) and no related record exists in the VAULT INDEX, state clearly: "I don't see any specific medical records regarding [condition] in your vault." Then, analyze their available vitals to see if they offer any insights.
      4. DO NOT LOOP: Never ask for permission to access data you already have or that clearly doesn't exist.
    `;

    const messages: ModelMessage[] = [];

    // Map history with AI SDK 6.0 standards
    (history || []).forEach((msg, idx) => {
      const role = msg.role.toLowerCase();
      try {
        if (role === 'assistant') {
          const rawToolCalls = msg.toolCalls ? JSON.parse(msg.toolCalls) : null;
          
          if (rawToolCalls && Array.isArray(rawToolCalls) && rawToolCalls.length > 0) {
            const parts: any[] = [];
            // In 6.0, assistant messages with tool calls SHOULD NOT have empty text parts.
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
                output: { type: 'text', value: msg.content }
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
      messages.push({
        role: 'tool',
        content: [
          {
            type: 'tool-result',
            toolCallId: activeToolCallId,
            toolName: 'request_medical_record',
            output: { type: 'text', value: `[AUTHORIZED RECORD CONTENT]:\n\n${prompt}` }
          }
        ]
      } as any);

      messages.push({
        role: 'user',
        content: "Analyze the authorized record and provide clinical synthesis."
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
          description: "Retrieve the full text of a document from the private vault using its ID.",
          inputSchema: z.object({ 
            record_id: z.string().describe("The ID of the record from the VAULT INDEX."), 
            reason: z.string().describe("Clinical justification for reading this record.") 
          })
        })
      }
    });

    return (result as any).toUIMessageStreamResponse();

  } catch (error: any) {
    console.error("AGENT_POST_ERROR:", error);
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
