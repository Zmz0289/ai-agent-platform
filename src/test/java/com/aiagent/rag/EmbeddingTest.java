package com.aiagent.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class EmbeddingTest {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    public void keywordEmbeddingTest() {
       String text =  "今天能发货吗？能我就下单了，具体什么时候发货？什么时候到货？";

        System.out.println("embeddingModel.embed(text) = " + embeddingModel.embed(text));


    }

}
