package com.yann.agent.entrance.service;

import com.yann.agent.entrance.config.SsoJwtProperties;
import com.yann.agent.entrance.dto.SsoJwtConfigResponse;
import com.yann.agent.entrance.support.JsonLog;
import com.yann.agent.entrance.support.SsoJwtConfigClient;
import com.yann.agent.entrance.support.SsoJwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@Service
public class SsoJwtConfigRefreshService {

	private static final Logger log = LoggerFactory.getLogger(SsoJwtConfigRefreshService.class);

	private final SsoJwtProperties properties;
	private final SsoJwtConfigClient configClient;
	private final SsoJwtVerifier ssoJwtVerifier;

	public SsoJwtConfigRefreshService(
			SsoJwtProperties properties,
			SsoJwtConfigClient configClient,
			SsoJwtVerifier ssoJwtVerifier
	) {
		this.properties = properties;
		this.configClient = configClient;
		this.ssoJwtVerifier = ssoJwtVerifier;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void refreshOnStartup() {
		if (!properties.isRefreshOnStartup()) {
			JsonLog.info(log, "sso.jwt.config.startup-refresh.skipped", "reason", "disabled");
			return;
		}
		refresh();
	}

	@Scheduled(fixedDelayString = "${sso.jwt.refresh-delay-millis:300000}")
	public void refresh() {
		if (!StringUtils.hasText(properties.getConfigUrl())) {
			JsonLog.warn(log, "sso.jwt.config.refresh.skipped", "reason", "missing_config_url");
			return;
		}
		try {
			SsoJwtConfigResponse remote = configClient.fetchConfig(URI.create(properties.getConfigUrl()));
			if (remote == null || remote.getIssuers().isEmpty()) {
				JsonLog.warn(log, "sso.jwt.config.refresh.skipped", "reason", "empty_remote_issuers");
				return;
			}
			Map<String, SsoJwtProperties.Issuer> oldIssuers = properties.getIssuers();
			boolean issuersChanged = !Objects.equals(oldIssuers, remote.getIssuers());
			boolean cookieNameChanged = StringUtils.hasText(remote.getCookieName())
					&& !Objects.equals(properties.getCookieName(), remote.getCookieName());
			if (!issuersChanged && !cookieNameChanged) {
				JsonLog.info(log, "sso.jwt.config.refresh.unchanged",
						"issuerCount", oldIssuers.size()
				);
				return;
			}
			if (StringUtils.hasText(remote.getCookieName())) {
				properties.setCookieName(remote.getCookieName());
			}
			if (issuersChanged) {
				properties.replaceIssuers(remote.getIssuers());
				ssoJwtVerifier.clearDecoderCache();
			}
			JsonLog.info(log, "sso.jwt.config.refresh.succeeded",
					"issuerCount", remote.getIssuers().size()
			);
		} catch (RuntimeException ex) {
			JsonLog.warn(log, "sso.jwt.config.refresh.failed",
					"errorType", ex.getClass().getSimpleName()
			);
		}
	}
}
