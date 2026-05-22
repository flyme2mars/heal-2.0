# Heal: Privacy-First Clinical AI Assistant

Heal is a secure, privacy-first clinical assistant application combining a **Jetpack Compose Android Client** with a **Next.js AI Agent Backend**. The application utilizes on-device OCR, localized AI summarization, local encryption, Android Health Connect, and a structured FHIR database, interfacing dynamically with a hosted clinical AI agent powered by Gemini.

---

## System Architecture

Heal split responsibilities between local processing (to guarantee absolute data privacy) and remote inference (for advanced clinical synthesis):

```
                       ┌──────────────────────────────────────────┐
                       │              Android Client              │
                       ├──────────────────────────────────────────┤
                       │  - ML Kit OCR & Local PDF Renderer       │
                       │  - Gemini Nano (Local Summarization)     │
                       │  - Encrypted Vault (Jetpack Security)    │
                       │  - Health Connect & Google FHIR Engine   │
                       └────────────────────┬─────────────────────┘
                                            │
                                  HTTPS (Secure Payload)
                                            │
                                            ▼
                       ┌──────────────────────────────────────────┐
                       │             Next.js Backend              │
                       ├──────────────────────────────────────────┤
                       │  - Vercel AI SDK (ai / @ai-sdk/google)   │
                       │  - Clinical AI Agent (Heal 2.0)          │
                       │  - Dynamic Tool: request_medical_record  │
                       └──────────────────────────────────────────┘
```

### 1. Android Client (`/android`)
*   **On-Device OCR & ML Kit**: Uses Google ML Kit Vision text recognition to extract text from imported medical documents (images & high-resolution rendered PDF pages).
*   **On-Device Summarization (Gemini Nano)**: Runs local heuristic-based analysis and semantic tagging on the extracted text to classify documents into clinical areas (e.g., Cardiac, Metabolic) and extract dates, summaries, and vital flags.
*   **Secure Encrypted Vault**: Stores the documents locally in private storage using `EncryptedFile` (AES-256 GCM) via Android Jetpack Security.
*   **Vitals & Health Connect**: Collects daily metrics (steps, average heart rate, SpO2, sleep, and active/total calories) directly from Google Health Connect.
*   **Structured FHIR Database**: Leverages the Google Android FHIR SDK to store patient profiles and clinical observations locally.

### 2. Next.js Agentic Backend (`/backend`)
*   **Heal 2.0 AI Agent**: Uses the Vercel AI SDK coupled with Gemini (`gemini-3.1-flash-lite-preview` / `gemini-3.5-flash-high`) to synthesize health history, vitals, and medical documents.
*   **Privacy-preserving Tool Calls**: To protect user privacy, the agent does *not* automatically ingest the content of all medical records. Instead, it receives only a metadata index of the vault and must explicitly call the `request_medical_record` tool, which prompts the client to decrypt and upload the specific document when authorized.

---

## Technology Stack

| Layer | Component / Library | Purpose |
| :--- | :--- | :--- |
| **Android** | Kotlin & Jetpack Compose | UI development and architecture |
| | Google Android FHIR SDK | On-device FHIR database & standard record formats |
| | Android Health Connect | Interoperable system-level health data access |
| | ML Kit Text Recognition | High-fidelity OCR for images and PDFs |
| | Jetpack Security Crypto | AES-256 GCM encrypted document storage |
| | Hilt & Room DB | Dependency injection & local document metadata store |
| **Backend** | Next.js 16 & React 19 | Serverless API routes and developer dashboard |
| | Vercel AI SDK (`ai`) | Decoupled tool calling and streaming framework |
| | Google AI SDK | Gemini model integration (`gemini-3.1-flash-lite`) |
| | Tailwind CSS | User interface styling |

---

## Directory Structure

```bash
MyChat/
├── android/                   # Android App Project
│   ├── app/
│   │   ├── src/main/java/com/example/mychat/
│   │   │   ├── data/          # DocumentManager, HealthManager, LocalIndexer, FHIR
│   │   │   ├── di/            # Hilt Modules
│   │   │   ├── network/       # SSE and POST network calls to Next.js API
│   │   │   └── ui/            # Compose Chat Screen, Vault Screen, Markdown Renderer
│   │   └── build.gradle.kts
│   └── build.gradle.kts
└── backend/                   # Next.js Server & AI Agent
    ├── src/
    │   ├── app/
    │   │   ├── api/chat/      # Heal 2.0 clinical agent API route
    │   │   └── page.tsx       # Local development panel
    │   └── types/             # Shared typescript declarations
    └── package.json
```

---

## Getting Started

### Prerequisites
*   Android Studio (Ladybug or newer)
*   Node.js (v18+)
*   A Gemini API Key (get one from Google AI Studio)

---

### Step 1: Run the Backend

1. Navigate to the backend directory:
   ```bash
   cd backend
   ```
2. Install dependencies:
   ```bash
   npm install
   ```
3. Set up your environment variables:
   Create a `.env.local` file in the `backend/` root:
   ```env
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
4. Start the development server:
   ```bash
   npm run dev
   ```
   *The server runs by default on `http://localhost:3000`.*

---

### Step 2: Configure & Run the Android App

1. Open the `/android` folder in **Android Studio**.
2. Update the network API endpoint in `HealNetworkClient.kt` to match your local server address (or use `http://10.0.2.2:3000` to connect from the Android emulator).
3. Connect an Android device or launch an emulator that has **Health Connect** installed.
4. Build and run the app.

> [!NOTE]
> Make sure to grant Health Connect permissions in your device settings to allow Heal to fetch step counts, heart rates, and blood oxygen levels.

---

## Privacy & Security Design

1. **Zero Cloud Storage by Default**: No user documents or full health history are stored on remote servers. The Next.js API is stateless and acts strictly as an inference channel.
2. **Encryption at Rest**: Documents saved in the private vault are encrypted locally using the Android Keystore system.
3. **On-Demand Authorization**: The backend clinical agent has visibility only of document *summaries* and *metadata*. Full documents are decrypted and sent only when the agent issues a verified tool call request.
