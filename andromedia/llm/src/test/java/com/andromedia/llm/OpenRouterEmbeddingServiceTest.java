package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.andromedia.common.EmbeddingConstants;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterEmbeddingServiceTest {

  @Test
  void callsOpenRouterEmbeddingsEndpoint() {
    EmbeddingProperties properties = new EmbeddingProperties();
    properties.setApiKey("test-key");
    properties.setBaseUrl("https://openrouter.ai/api/v1");
    properties.setModel(EmbeddingConstants.DEFAULT_MODEL);
    properties.setDimensions(3);
    properties.setBatchSize(20);

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(jsonPath("$.model").value(EmbeddingConstants.DEFAULT_MODEL))
        .andExpect(jsonPath("$.input[0]").value("hello"))
        .andRespond(
            withSuccess(
                """
                {
                  "data": [
                    { "embedding": [0.1, 0.2, 0.3], "index": 0 }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    OpenRouterEmbeddingService service = new OpenRouterEmbeddingService(properties, builder.build());
    float[] vector = service.embed("hello");

    assertEquals(3, vector.length);
    assertEquals(0.1f, vector[0], 0.0001f);
    server.verify();
  }

  @Test
  void batchesMultipleInputsInOneRequest() {
    EmbeddingProperties properties = new EmbeddingProperties();
    properties.setApiKey("test-key");
    properties.setBaseUrl("https://openrouter.ai/api/v1");
    properties.setModel(EmbeddingConstants.DEFAULT_MODEL);
    properties.setDimensions(2);
    properties.setBatchSize(20);

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/embeddings"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.input.length()").value(3))
        .andRespond(
            withSuccess(
                """
                {
                  "data": [
                    { "embedding": [0.1, 0.2], "index": 0 },
                    { "embedding": [0.3, 0.4], "index": 1 },
                    { "embedding": [0.5, 0.6], "index": 2 }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    OpenRouterEmbeddingService service = new OpenRouterEmbeddingService(properties, builder.build());
    var vectors = service.embedAll(List.of("a", "b", "c"));

    assertEquals(3, vectors.size());
    assertEquals(0.3f, vectors.get(1)[0], 0.0001f);
    server.verify();
  }
}
