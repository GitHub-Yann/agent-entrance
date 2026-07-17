package com.yann.agent.entrance.support;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.yann.agent.entrance.model.UserContext;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextResolverTest {

	private static final String COOKIE_NAME = "SSO_JWT";
	private static final String ISSUER = "https://sso.tenant-a.example.com";
	private static final String PUBLIC_KEY_URI = ISSUER + "/oauth/public-key";
	private static final String KEY_ID = "tenant-a-key";

	private RSAPrivateKey privateKey;
	private String publicKey;
	private UserContextResolver resolver;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		privateKey = (RSAPrivateKey) keyPair.getPrivate();
		publicKey = toPem(keyPair);

		SsoJwtProperties properties = new SsoJwtProperties();
		properties.setCookieName(COOKIE_NAME);
		SsoJwtProperties.Issuer issuer = new SsoJwtProperties.Issuer();
		issuer.setPublicKeyUri(PUBLIC_KEY_URI);
		properties.getIssuers().put(ISSUER, issuer);
		resolver = new UserContextResolver(
				new TokenExtractor(properties),
				new SsoJwtVerifier(properties, publicKeyClient(), new PemPublicKeyParser())
		);
	}

	@Test
	void resolvesUserContextFromBearerAuthorizationToken() throws Exception {
		String token = signedJwt("u-1", "tenant-a", "Yann Chen", "Yann");
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.post("/api/conversations/c-1/messages/stream")
						.header("Authorization", "Bearer " + token)
		);

		UserContext context = resolver.resolve(exchange, "correlation-1");

		assertThat(context.userId()).isEqualTo("u-1");
		assertThat(context.username()).isEqualTo("Yann Chen");
		assertThat(context.tenantId()).isEqualTo("tenant-a");
		assertThat(context.tenantName()).isEqualTo("Yann");
		assertThat(context.accessToken()).isEqualTo(token);
		assertThat(context.correlation()).isEqualTo("correlation-1");
	}

	@Test
	void resolvesUserContextFromJwtCookieWhenAuthorizationHeaderMissing() throws Exception {
		String token = signedJwt("u-2", "tenant-b", "Cayce", "Yann Cloud");
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.post("/api/conversations/c-1/messages/stream")
						.cookie(new HttpCookie(COOKIE_NAME, token))
		);

		UserContext context = resolver.resolve(exchange, "correlation-2");

		assertThat(context.userId()).isEqualTo("u-2");
		assertThat(context.tenantId()).isEqualTo("tenant-b");
		assertThat(context.accessToken()).isEqualTo(token);
		assertThat(context.correlation()).isEqualTo("correlation-2");
	}

	@Test
	void rejectsTamperedToken() throws Exception {
		String token = signedJwt("u-1", "tenant-a", "Yann Chen", "Yann") + "tampered";
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.post("/api/conversations/c-1/messages/stream")
						.header("Authorization", "Bearer " + token)
		);

		assertThatThrownBy(() -> resolver.resolve(exchange))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Invalid JWT token");
	}

	private String signedJwt(String userId, String tenantId, String username, String tenantName) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId)
				.claim("userId", userId)
				.claim("tenantId", tenantId)
				.claim("username", username)
				.claim("tenantName", tenantName)
				.issuer(ISSUER)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(300)))
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY_ID).build(),
				claims
		);
		jwt.sign(new RSASSASigner(privateKey));
		return jwt.serialize();
	}

	private String toPem(KeyPair keyPair) {
		String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
		return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
	}

	private SsoPublicKeyClient publicKeyClient() {
		return new SsoPublicKeyClient(org.springframework.web.reactive.function.client.WebClient.builder()) {
			@Override
			public String fetchPublicKey(java.net.URI publicKeyUri) {
				assertThat(publicKeyUri.toString()).isEqualTo(PUBLIC_KEY_URI);
				return publicKey;
			}
		};
	}
}
