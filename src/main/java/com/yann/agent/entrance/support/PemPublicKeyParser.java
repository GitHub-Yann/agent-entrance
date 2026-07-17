package com.yann.agent.entrance.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JOSEException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Base64;

@Component
public class PemPublicKeyParser {

	public RSAPublicKey parseRsaPublicKey(String content) {
		return parseRsaPublicKey(content, null);
	}

	public RSAPublicKey parseRsaPublicKey(String content, String keyId) {
		if (StringUtils.hasText(content) && content.trim().startsWith("{")) {
			return parseJwksPublicKey(content, keyId);
		}
		return parsePemPublicKey(content);
	}

	private RSAPublicKey parsePemPublicKey(String pem) {
		try {
			String normalized = pem
					.replace("-----BEGIN PUBLIC KEY-----", "")
					.replace("-----END PUBLIC KEY-----", "")
					.replaceAll("\\s", "");
			byte[] der = Base64.getDecoder().decode(normalized);
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		} catch (IllegalArgumentException | GeneralSecurityException ex) {
			throw new IllegalStateException("Invalid SSO RSA public key", ex);
		}
	}

	private RSAPublicKey parseJwksPublicKey(String jwks, String keyId) {
		try {
			JWKSet jwkSet = JWKSet.parse(jwks);
			RSAKey rsaKey = selectRsaKey(jwkSet.getKeys(), keyId);
			return rsaKey.toRSAPublicKey();
		} catch (ParseException | JOSEException ex) {
			throw new IllegalStateException("Invalid SSO RSA public key", ex);
		}
	}

	private RSAKey selectRsaKey(List<JWK> keys, String keyId) {
		if (StringUtils.hasText(keyId)) {
			return keys.stream()
					.filter(RSAKey.class::isInstance)
					.map(RSAKey.class::cast)
					.filter(key -> keyId.equals(key.getKeyID()))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("SSO RSA public key not found by kid"));
		}
		return keys.stream()
				.filter(RSAKey.class::isInstance)
				.map(RSAKey.class::cast)
				.filter(this::isSignatureKey)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("SSO RSA public key not found"));
	}

	private boolean isSignatureKey(RSAKey key) {
		return key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse());
	}
}
