package com.rag.demo.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    @Autowired
    private OpenAiStreamingChatModel streamingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    public void streamAnswer(String question, StreamingCallback callback) {
        // 1. Embed the question
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Search top 3 relevant chunks from Milvus
        List<TextSegment> relevant = embeddingStore.findRelevant(questionEmbedding, 3).stream()
                .map(match -> (TextSegment) match.embedded())
                .collect(Collectors.toList());

        // 3. Build prompt with context
        String context = relevant.stream()
                .map(TextSegment::text)
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = "You are a helpful assistant. Answer the question based on the provided context.\n\n"
                + "Context:\n" + context + "\n\n"
                + "Question: " + question;

        // 4. Stream the answer via DeepSeek
        streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    public interface StreamingCallback {
        void onToken(String token);
        void onComplete();
        void onError(Throwable error);
    }
}
