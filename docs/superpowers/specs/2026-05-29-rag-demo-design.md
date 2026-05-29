# RAG Demo - Design Spec

## 1. Overview

Build a RAG (Retrieval-Augmented Generation) demo application on top of the existing Spring Boot + LangChain4j scaffold. Users can upload or pre-place PDF/TXT documents, then ask questions via a web chat UI. Answers are streamed token-by-token via SSE.

## 2. Architecture

```
Browser (SSE stream)  ←→  Controller  ←→  Service  ←→  Infrastructure (Milvus / DeepSeek)
```

Three layers:

| Layer | Responsibility |
|---|---|
| **Controller** | REST endpoints: document upload, chat (SSE), static page serving |
| **Service** | `DocumentIngestionService` — parse, chunk, embed, store. `RagQueryService` — retrieve, prompt, stream LLM response |
| **Infrastructure** | Milvus vector store, DeepSeek LLM/Embedding via OpenAI-compatible API, document parsers |

## 3. Interaction — Chat (SSE)

- **Endpoint:** `POST /api/chat` with JSON body `{ "question": "..." }`
- **Response:** `Content-Type: text/event-stream`
- **Flow:**
  1. Embed the question via DeepSeek Embedding
  2. Search top-K relevant chunks from Milvus
  3. Build prompt: system instruction + retrieved context + user question
  4. Call DeepSeek Chat with streaming → emit tokens as SSE events
- **SSE format:**
  - `data: {"token":"..."}\n\n` per token
  - `data: [DONE]\n\n` when complete
- **Scope:** searches across ALL ingested documents

## 4. Interaction — Document Ingestion

- **Startup scan:** reads directory configured in `app.document-scan-path` (default `data/docs/`), parses all PDF/TXT files, chunks (~500 tokens, 50 token overlap), embeds, stores in Milvus
- **Upload:** `POST /api/documents/upload` (multipart/form-data), same processing pipeline, returns `{ "documentName": "...", "chunks": N }`
- **Storage model per chunk:** `{ vector, originalText, documentName }`

## 5. Configuration

```properties
server.port=8080

# DeepSeek (OpenAI-compatible)
langchain4j.open-ai.chat-model.api-key=${DEEPSEEK_API_KEY}
langchain4j.open-ai.chat-model.base-url=https://api.deepseek.com
langchain4j.open-ai.chat-model.model-name=deepseek-chat
langchain4j.open-ai.chat-model.temperature=0.7

langchain4j.open-ai.embedding-model.api-key=${DEEPSEEK_API_KEY}
langchain4j.open-ai.embedding-model.base-url=https://api.deepseek.com
langchain4j.open-ai.embedding-model.model-name=deepseek-embedding

# Milvus
milvus.host=localhost
milvus.port=19530
milvus.collection-name=rag_demo
milvus.dimension=2048

# Document scan path
app.document-scan-path=data/docs
```

## 6. Frontend

Single HTML file at `src/main/resources/static/index.html`:

- Chat message list + bottom input bar
- SSE streaming with typewriter effect (fetch + ReadableStream)
- Document list display (uploaded docs)
- Upload button
- Pure HTML/CSS/JS, no framework, mobile-friendly

## 7. Non-goals (YAGNI)

- No authentication/authorization
- No async document processing (sync only)
- No conversation history persistence (in-memory only)
- No streaming of uploaded file content to frontend
