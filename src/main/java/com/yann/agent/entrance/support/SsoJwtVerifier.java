package com.yann.agent.entrance.support;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class SsoJwtVerifier {

	private static final Logger log = LoggerFactory.getLogger(SsoJwtVerifier.class);

	private final SsoJwtProperties properties;
	private final SsoPublicKeyClient publicKeyClient;
	private final PemPublicKeyParser publicKeyParser;
	private final Map<String, NimbusJwtDecoder> decoders = new ConcurrentHashMap<>();

	public SsoJwtVerifier(
			SsoJwtProperties properties,
			SsoPublicKeyClient publicKeyClient,
			PemPublicKeyParser publicKeyParser
	) {
		this.properties = properties;
		this.publicKeyClient = publicKeyClient;
		this.publicKeyParser = publicKeyParser;
	}

	public Jwt verify(String token) {
		if (!StringUtils.hasText(token)) {
			JsonLog.warn(log, "auth.jwt.verify.rejected", "reason", "empty_token");
			throw new ResponseStatusException(UNAUTHORIZED, "Missing JWT token");
		}
		try {
			SignedJWT signedJwt = parseSignedJwt(token);
			String issuer = extractIssuer(signedJwt);
			Jwt jwt = decoder(issuer, signedJwt.getHeader().getKeyID()).decode(token.trim());
			JsonLog.info(log, "auth.jwt.verify.succeeded",
					"subject", jwt.getSubject(),
					"issuer", jwt.getIssuer(),
					"expiresAt", jwt.getExpiresAt()
			);
			return jwt;
		} catch (JwtException | IllegalStateException ex) {
			Throwable rootCause = rootCause(ex);
			JsonLog.warn(log, "auth.jwt.verify.failed",
					"errorType", ex.getClass().getSimpleName(),
					"errorMessage", safeErrorMessage(ex),
					"rootErrorType", rootCause.getClass().getSimpleName(),
					"rootErrorMessage", safeErrorMessage(rootCause)
			);
			throw new ResponseStatusException(UNAUTHORIZED, "Invalid JWT token", ex);
		}
	}

	private NimbusJwtDecoder decoder(String issuer, String keyId) {
		String cacheKey = decoderCacheKey(issuer, keyId);
		NimbusJwtDecoder cached = decoders.get(cacheKey);
		if (cached != null) {
			JsonLog.info(log, "auth.jwt.decoder.cache.hit", "issuer", issuer, "kid", keyId);
			return cached;
		}
		return decoders.computeIfAbsent(cacheKey, ignored -> loadDecoder(issuer, keyId));
	}

	public void clearDecoderCache() {
		int cacheSize = decoders.size();
		decoders.clear();
		JsonLog.info(log, "auth.jwt.decoder.cache.cleared", "cacheSize", cacheSize);
	}

	private NimbusJwtDecoder loadDecoder(String issuer, String keyId) {
		URI publicKeyUri = publicKeyUri(issuer);
		String publicKey = publicKeyClient.fetchPublicKey(publicKeyUri);
		if (!StringUtils.hasText(publicKey)) {
			JsonLog.warn(log, "auth.jwt.public-key.empty",
					"issuer", issuer,
					"publicKeyUri", publicKeyUri
			);
			throw new IllegalStateException("SSO JWT public key is empty");
		}
		JsonLog.info(log, "auth.jwt.public-key.loaded",
				"issuer", issuer,
				"kid", keyId,
				"publicKeyUri", publicKeyUri
		);
		return NimbusJwtDecoder.withPublicKey(publicKeyParser.parseRsaPublicKey(publicKey, keyId)).build();
	}

	private String decoderCacheKey(String issuer, String keyId) {
		return issuer + "#" + (StringUtils.hasText(keyId) ? keyId : "");
	}

	private URI publicKeyUri(String issuer) {
		SsoJwtProperties.Issuer issuerProperties = properties.getIssuers().get(issuer);
		if (issuerProperties == null || !StringUtils.hasText(issuerProperties.getPublicKeyUri())) {
			JsonLog.warn(log, "auth.jwt.issuer.unsupported", "issuer", issuer);
			throw new IllegalStateException("SSO issuer is not configured: " + issuer);
		}
		return URI.create(issuerProperties.getPublicKeyUri());
	}

	private SignedJWT parseSignedJwt(String token) {
		try {
			return SignedJWT.parse(token.trim());
		} catch (Exception ex) {
			throw new BadJwtException("Invalid JWT", ex);
		}
	}

	private String extractIssuer(SignedJWT jwt) {
		try {
			String issuer = jwt.getJWTClaimsSet().getIssuer();
			if (!StringUtils.hasText(issuer)) {
				throw new BadJwtException("Missing JWT issuer");
			}
			return issuer;
		} catch (Exception ex) {
			throw new BadJwtException("Invalid JWT issuer", ex);
		}
	}

	private Throwable rootCause(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	private String safeErrorMessage(Throwable throwable) {
		if (throwable == null || !StringUtils.hasText(throwable.getMessage())) {
			return null;
		}
		String message = throwable.getMessage()
				.replaceAll("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}", "[JWT_REDACTED]");
		if (message.length() > 300) {
			return message.substring(0, 300) + "...";
		}
		return message;
	}
}
