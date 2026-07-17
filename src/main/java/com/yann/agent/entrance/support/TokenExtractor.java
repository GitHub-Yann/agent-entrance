package com.yann.agent.entrance.support;

import com.yann.agent.entrance.config.SsoJwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class TokenExtractor {

	private static final Logger log = LoggerFactory.getLogger(TokenExtractor.class);
	private static final String BEARER_PREFIX = "Bearer ";

	private final SsoJwtProperties properties;

	public TokenExtractor(SsoJwtProperties properties) {
		this.properties = properties;
	}

	public String extract(ServerWebExchange exchange) {
		String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (StringUtils.hasText(authorization)) {
			String token = stripBearerPrefix(authorization);
			JsonLog.info(log, "auth.token.extracted",
					"source", "authorization",
					"tokenLength", token.length()
			);
			return token;
		}
		HttpCookie cookie = exchange.getRequest().getCookies().getFirst(properties.getCookieName());
		if (cookie != null && StringUtils.hasText(cookie.getValue())) {
			String token = cookie.getValue().trim();
			JsonLog.info(log, "auth.token.extracted",
					"source", "cookie",
					"cookieName", properties.getCookieName(),
					"tokenLength", token.length()
			);
			return token;
		}
		JsonLog.warn(log, "auth.token.missing",
				"cookieName", properties.getCookieName()
		);
		throw new ResponseStatusException(UNAUTHORIZED, "Missing Authorization header or JWT cookie");
	}

	private String stripBearerPrefix(String authorization) {
		String token = authorization.trim();
		if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return token.substring(BEARER_PREFIX.length()).trim();
		}
		return token;
	}
}
