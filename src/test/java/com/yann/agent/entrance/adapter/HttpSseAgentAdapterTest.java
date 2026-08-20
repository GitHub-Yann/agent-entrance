package com.yann.agent.entrance.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yann.agent.entrance.dto.AgentInvokeRequest;
import com.yann.agent.entrance.dto.AgentStreamChunk;
import com.yann.agent.entrance.model.AgentProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSseAgentAdapterTest {

	@Test
	void parsesTextAndContentBlocksFromSse() {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			String body = """
					event: message_start
					data: {"messageId":"m-1"}

					event: delta
					data: {"text":"hello "}

					event: content_block
					data: {"type":"chart","title":"趋势图","format":"bar","labels":["周一"],"values":[1]}

					event: message_end
					data: {"messageId":"m-1","status":"SUCCESS"}
					""";
			return reactor.core.publisher.Mono.just(
					org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.OK)
							.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
							.body(body)
							.build()
			);
		});

		HttpSseAgentAdapter adapter = new HttpSseAgentAdapter(builder, new ObjectMapper());
		AgentInvokeRequest request = new AgentInvokeRequest(
				"dev-assistant",
				"tenant-a",
				"u-1",
				"c-1",
				null,
				"hi",
				URI.create("http://localhost/mock"),
				"corr-1"
		);

		StepVerifier.create(adapter.stream(request))
				.assertNext(chunk -> assertThat(chunk.event()).isEqualTo("message_start"))
				.assertNext(chunk -> {
					assertThat(chunk.event()).isEqualTo("delta");
					assertThat(chunk.text()).isEqualTo("hello ");
				})
				.assertNext(chunk -> {
					assertThat(chunk.event()).isEqualTo("content_block");
					assertThat(chunk.blockType()).isEqualTo("chart");
				})
				.assertNext(chunk -> assertThat(chunk.event()).isEqualTo("message_end"))
				.verifyComplete();
	}
}
