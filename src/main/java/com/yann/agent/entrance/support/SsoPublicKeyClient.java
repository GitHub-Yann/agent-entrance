package com.yann.agent.entrance.support;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;

@Component
public class SsoPublicKeyClient {

	private final WebClient webClient;

	public SsoPublicKeyClient(WebClient.Builder webClientBuilder) {
		this.webClient = webClientBuilder.build();
	}

	public String fetchPublicKey(URI publicKeyUri) {
		return webClient.get()
				.uri(publicKeyUri)
				.retrieve()
				.bodyToMono(String.class)
				.block();
	}
}
