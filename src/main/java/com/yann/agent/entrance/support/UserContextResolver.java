package com.yann.agent.entrance.support;

import com.yann.agent.entrance.model.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class UserContextResolver {

	private static final Logger log = LoggerFactory.getLogger(UserContextResolver.class);

	private final TokenExtractor tokenExtractor;
	private final SsoJwtVerifier ssoJwtVerifier;

	public UserContextResolver(TokenExtractor tokenExtractor, SsoJwtVerifier ssoJwtVerifier) {
		this.tokenExtractor = tokenExtractor;
		this.ssoJwtVerifier = ssoJwtVerifier;
	}

	public UserContext resolve(ServerWebExchange exchange) {
		return resolve(exchange, CorrelationContext.current());
	}

	public UserContext resolve(ServerWebExchange exchange, String correlation) {
		String token = tokenExtractor.extract(exchange);
		Jwt jwt = ssoJwtVerifier.verify(token);
		String userId = requiredClaim(jwt, "person_id", jwt.getSubject());
		String tenantId = requiredClaim(jwt, "tenant", claim(jwt, "tenant_id"));
		String username = optionalClaim(jwt, "preferred_username", claim(jwt, "preferred_username"), userId);
		String tenantName = optionalClaim(jwt, "tenant", claim(jwt, "tenant_name"), tenantId);
		JsonLog.info(log, "auth.user.context.resolved",
				"userId", userId,
				"tenantId", tenantId,
				"username", username
		);
		return new UserContext(userId, username, tenantId, tenantName, token, correlation);
	}

	private String requiredClaim(Jwt jwt, String claimName, String fallback) {
		String value = optionalClaim(jwt, claimName, fallback, null);
		if (!StringUtils.hasText(value)) {
			JsonLog.warn(log, "auth.jwt.claim.missing", "claimName", claimName);
			throw new ResponseStatusException(UNAUTHORIZED, "Missing JWT claim: " + claimName);
		}
		return value;
	}

	private String optionalClaim(Jwt jwt, String claimName, String firstFallback, String secondFallback) {
		String value = claim(jwt, claimName);
		if (StringUtils.hasText(value)) {
			return value;
		}
		if (StringUtils.hasText(firstFallback)) {
			return firstFallback.trim();
		}
		return StringUtils.hasText(secondFallback) ? secondFallback.trim() : null;
	}

	private String claim(Jwt jwt, String claimName) {
		Object value = jwt.getClaims().get(claimName);
		return value == null ? null : value.toString();
	}
}

