package com.rag.demo.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    @Autowired
    private OpenAiStreamingChatModel streamingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    public void streamAnswer(String question, StreamingCallback callback) {
        // 1. Embed the question
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Search top 10, then filter by score threshold
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(questionEmbedding, 10);
        log.info("Found {} matches total", matches.size());

        for (EmbeddingMatch<TextSegment> m : matches) {
            log.info("  score={} content={}", m.score(),
                    m.embedded().text().substring(0, Math.min(80, m.embedded().text().length())));
        }

        // 3. Filter by relevance score
        // BGE 模型分数整体偏高，需要较高阈值才能确定真正相关
        double threshold = 0.80;
        List<TextSegment> relevant = matches.stream()
                .filter(m -> m.score() >= threshold)
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());

        log.info("After filtering (score>={}): {} chunks", threshold, relevant.size());

        // 4. Build prompt
        String prompt;
        if (relevant.isEmpty()) {
            // 没有相关文档 → 普通对话模式，让 LLM 自由回答
            log.info("No relevant docs found, using normal chat mode");
            prompt = "你是一个智能助手。请回答用户的问题。\n\n"
                    + "## 问题\n" + question;
        } else {
            // 有相关文档 → RAG 模式，基于文档回答
            String context = relevant.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining("\n\n---\n\n"));
            prompt = "你是一个数据分析助手。请基于以下文档内容回答问题。\n\n"
                    + "## 文档内容\n"
                    + context + "\n\n"
                    + "## 要求\n"
                    + "- 只基于上面的文档内容回答，不要使用你自己的知识\n"
                    + "- 如果文档内容中没有相关信息，请直接回复: 根据文档内容，没有找到相关信息\n"
                    + "- 回答时引用具体的行/数据来支撑你的结论\n"
                    + "- 用中文回答\n\n"
                    + "## 问题\n" + question;
        }

        // 5. Stream the answer via DeepSeek
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
