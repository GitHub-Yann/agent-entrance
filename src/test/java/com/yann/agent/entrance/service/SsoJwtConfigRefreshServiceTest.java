package com.yann.agent.entrance.service;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.yann.agent.entrance.dto.SsoJwtConfigResponse;
import com.yann.agent.entrance.support.PemPublicKeyParser;
import com.yann.agent.entrance.support.SsoJwtConfigClient;
import com.yann.agent.entrance.support.SsoJwtVerifier;
import com.yann.agent.entrance.support.SsoPublicKeyClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SsoJwtConfigRefreshServiceTest {

	private static final String CONFIG_URL = "https://config.example.com/sso-jwt";
	private static final String ISSUER = "https://sso.tenant-a.example.com";
	private static final String PUBLIC_KEY_URI = ISSUER + "/oauth/public-key";

	@Test
	void refreshesSsoJwtPropertiesFromRemoteConfig() {
		SsoJwtProperties properties = new SsoJwtProperties();
		properties.setConfigUrl(CONFIG_URL);
		AtomicInteger clearCount = new AtomicInteger();
		SsoJwtConfigRefreshService service = new SsoJwtConfigRefreshService(
				properties,
				configClient(remoteConfig("SSO_JWT", PUBLIC_KEY_URI), new AtomicInteger()),
				verifier(clearCount)
		);

		service.refresh();

		assertThat(properties.getCookieName()).isEqualTo("SSO_JWT");
		assertThat(properties.getIssuers()).containsKey(ISSUER);
		assertThat(properties.getIssuers().get(ISSUER).getPublicKeyUri()).isEqualTo(PUBLIC_KEY_URI);
		assertThat(clearCount).hasValue(1);
	}

	@Test
	void keepsDecoderCacheWhenRemoteIssuersAreUnchanged() {
		SsoJwtProperties properties = new SsoJwtProperties();
		properties.setConfigUrl(CONFIG_URL);
		properties.replaceIssuers(remoteConfig("SSO_JWT", PUBLIC_KEY_URI).getIssuers());
		AtomicInteger clearCount = new AtomicInteger();
		SsoJwtConfigRefreshService service = new SsoJwtConfigRefreshService(
				properties,
				configClient(remoteConfig("SSO_JWT", PUBLIC_KEY_URI), new AtomicInteger()),
				verifier(clearCount)
		);

		service.refresh();

		assertThat(clearCount).hasValue(0);
	}

	@Test
	void skipsRefreshWhenConfigUrlIsMissing() {
		SsoJwtProperties properties = new SsoJwtProperties();
		AtomicInteger fetchCount = new AtomicInteger();
		AtomicInteger clearCount = new AtomicInteger();
		SsoJwtConfigRefreshService service = new SsoJwtConfigRefreshService(
				properties,
				configClient(remoteConfig("SSO_JWT", PUBLIC_KEY_URI), fetchCount),
				verifier(clearCount)
		);

		service.refresh();

		assertThat(fetchCount).hasValue(0);
		assertThat(clearCount).hasValue(0);
		assertThat(properties.getIssuers()).isEmpty();
	}

	private SsoJwtConfigResponse remoteConfig(String cookieName, String publicKeyUri) {
		SsoJwtConfigResponse response = new SsoJwtConfigResponse();
		response.setCookieName(cookieName);
		SsoJwtProperties.Issuer issuer = new SsoJwtProperties.Issuer();
		issuer.setPublicKeyUri(publicKeyUri);
		response.getIssuers().put(ISSUER, issuer);
		return response;
	}

	private SsoJwtConfigClient configClient(SsoJwtConfigResponse response, AtomicInteger fetchCount) {
		return new SsoJwtConfigClient(WebClient.builder()) {
			@Override
			public SsoJwtConfigResponse fetchConfig(URI configUri) {
				assertThat(configUri.toString()).isEqualTo(CONFIG_URL);
				fetchCount.incrementAndGet();
				return response;
			}
		};
	}

	private SsoJwtVerifier verifier(AtomicInteger clearCount) {
		return new SsoJwtVerifier(
				new SsoJwtProperties(),
				new SsoPublicKeyClient(WebClient.builder()),
				new PemPublicKeyParser()
		) {
			@Override
			public void clearDecoderCache() {
				clearCount.incrementAndGet();
			}
		};
	}
}
