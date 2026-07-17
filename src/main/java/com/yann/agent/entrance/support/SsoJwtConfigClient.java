package com.yann.agent.entrance.support;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.yann.agent.entrance.dto.SsoJwtConfigResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Component
public class SsoJwtConfigClient {

	private final WebClient webClient;

	public SsoJwtConfigClient(WebClient.Builder webClientBuilder) {
		this.webClient = webClientBuilder.build();
	}

	public SsoJwtConfigResponse fetchConfig(URI configUri) {

		SsoJwtConfigResponse response = new SsoJwtConfigResponse();
		response.setCookieName("KEYCLOAK_ADAPTER_STATE");
		Map<String, SsoJwtProperties.Issuer> issuers = new HashMap<>();
		SsoJwtProperties.Issuer issuer = new SsoJwtProperties.Issuer();
		issuer.setPublicKeyUri("https://sso-qa.gaiaworkforce.com/auth/realms/portal/protocol/openid-connect/certs");
		issuers.put("https://sso-qa.gaiaworkforce.com/auth/realms/portal", issuer);
		response.setIssuers(issuers);
		
		return response;
		
		// return webClient.get()
		// 		.uri(configUri)
		// 		.retrieve()
		// 		.bodyToMono(SsoJwtConfigResponse.class)
		// 		.block();
	}
}
