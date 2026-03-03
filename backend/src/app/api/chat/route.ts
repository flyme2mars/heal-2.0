import { NextRequest, NextResponse } from "next/server";
import { google } from "@ai-sdk/google";
import { streamText } from "ai";
import { verifyAppCheck } from "@/lib/firebase";
import { ChatRequest } from "@/types/chat";

export async function POST(req: NextRequest) {
  try {
    // 1. Security Check (Firebase App Check)
    if (process.env.NODE_ENV === "production") {
      const appCheckToken = req.headers.get("X-Firebase-AppCheck");
      await verifyAppCheck(appCheckToken || undefined);
    }

    // 2. Parse the Request
    const body: ChatRequest = await req.json();
    const { prompt, context } = body;

    if (!prompt) {
      return NextResponse.json({ error: "Missing prompt" }, { status: 400 });
    }

    // 3. Construct System Instruction
    const systemInstruction = `
      You are Remini, a secure health AI agent.
      
      HEALTH CONTEXT:
      - Steps (today): ${context.health_connect?.steps || 'No data'}
      - Heart Rate (avg): ${context.health_connect?.avg_hr || 'No data'} bpm
      
      MEDICAL HISTORY:
      ${context.fhir_records?.map(r => `- ${r.resourceType}: ${JSON.stringify(r)}`).join('\n') || 'No records found.'}
      
      MEMORY (Markdown files):
      ${Object.entries(context.memory_snapshot).map(([file, content]) => `File: ${file}\n${content}`).join('\n---\n')}
      
      INSTRUCTIONS:
      1. Answer the user's health questions using the provided context.
      2. Maintain a professional yet magical health companion persona.
    `;

    // 4. Stream Response
    const result = await streamText({
      model: google("gemini-1.5-flash"),
      system: systemInstruction,
      prompt: prompt,
    });

    return result.toTextStreamResponse();
  } catch (error: any) {
    console.error("Chat API error:", error);
    return NextResponse.json(
      { error: error.message || "Internal Server Error" },
      { status: error.status || 500 }
    );
  }
}
