package com.yann.agent.entrance.support;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsoJwtVerifierTest {

	private static final String ISSUER = "https://sso.tenant-a.example.com";
	private static final String PUBLIC_KEY_URI = ISSUER + "/oauth/public-key";
	private static final String KEY_ID = "tenant-a-key";

	@Test
	void cachesPublicKeyDecoderByIssuer() throws Exception {
		KeyPair keyPair = keyPair();
		AtomicInteger fetchCount = new AtomicInteger();
		SsoJwtVerifier verifier = newVerifier(toPem(keyPair), fetchCount);

		verifier.verify(signedJwt(keyPair, "u-1", ISSUER));
		verifier.verify(signedJwt(keyPair, "u-2", ISSUER));

		assertThat(fetchCount).hasValue(1);
	}

	@Test
	void verifiesTokenWithJwksPublicKey() throws Exception {
		KeyPair keyPair = keyPair();
		SsoJwtVerifier verifier = newVerifier(toJwks(keyPair), new AtomicInteger());

		assertThat(verifier.verify(signedJwt(keyPair, "u-1", ISSUER)).getSubject()).isEqualTo("u-1");
	}

	@Test
	void rejectsTokenWhenIssuerIsNotConfigured() throws Exception {
		KeyPair keyPair = keyPair();
		SsoJwtVerifier verifier = new SsoJwtVerifier(new SsoJwtProperties(), publicKeyClient("", new AtomicInteger()), new PemPublicKeyParser());

		assertThatThrownBy(() -> verifier.verify(signedJwt(keyPair, "u-1", ISSUER)))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Invalid JWT token");
	}

	private SsoJwtVerifier newVerifier(String publicKey, AtomicInteger fetchCount) {
		SsoJwtProperties properties = new SsoJwtProperties();
		SsoJwtProperties.Issuer issuer = new SsoJwtProperties.Issuer();
		issuer.setPublicKeyUri(PUBLIC_KEY_URI);
		properties.getIssuers().put(ISSUER, issuer);
		return new SsoJwtVerifier(properties, publicKeyClient(publicKey, fetchCount), new PemPublicKeyParser());
	}

	private SsoPublicKeyClient publicKeyClient(String publicKey, AtomicInteger fetchCount) {
		return new SsoPublicKeyClient(WebClient.builder()) {
			@Override
			public String fetchPublicKey(URI publicKeyUri) {
				assertThat(publicKeyUri.toString()).isEqualTo(PUBLIC_KEY_URI);
				fetchCount.incrementAndGet();
				return publicKey;
			}
		};
	}

	private KeyPair keyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private String signedJwt(KeyPair keyPair, String userId, String issuer) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId)
				.issuer(issuer)
				.claim("userId", userId)
				.claim("tenantId", "tenant-a")
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(300)))
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY_ID).build(),
				claims
		);
		jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
		return jwt.serialize();
	}

	private String toPem(KeyPair keyPair) {
		String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
		return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
	}

	private String toJwks(KeyPair keyPair) {
		RSAKey rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
				.keyID(KEY_ID)
				.algorithm(JWSAlgorithm.RS256)
				.keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
				.build();
		return "{\"keys\":[" + rsaKey.toPublicJWK().toJSONString() + "]}";
	}
}
