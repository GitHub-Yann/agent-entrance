package com.yann.agent.entrance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@ConfigurationProperties(prefix = "sso.jwt")
public class SsoJwtProperties {

	private String cookieName = "SSO_TOKEN";
	private String configUrl;
	private boolean refreshOnStartup = true;
	private long refreshDelayMillis = 300000;
	private Map<String, Issuer> issuers = new ConcurrentHashMap<>();

	public String getCookieName() {
		return cookieName;
	}

	public void setCookieName(String cookieName) {
		this.cookieName = cookieName;
	}

	public String getConfigUrl() {
		return configUrl;
	}

	public void setConfigUrl(String configUrl) {
		this.configUrl = configUrl;
	}

	public boolean isRefreshOnStartup() {
		return refreshOnStartup;
	}

	public void setRefreshOnStartup(boolean refreshOnStartup) {
		this.refreshOnStartup = refreshOnStartup;
	}

	public long getRefreshDelayMillis() {
		return refreshDelayMillis;
	}

	public void setRefreshDelayMillis(long refreshDelayMillis) {
		this.refreshDelayMillis = refreshDelayMillis;
	}

	public Map<String, Issuer> getIssuers() {
		return issuers;
	}

	public void setIssuers(Map<String, Issuer> issuers) {
		replaceIssuers(issuers);
	}

	public void replaceIssuers(Map<String, Issuer> issuers) {
		Map<String, Issuer> nextIssuers = issuers == null ? new HashMap<>() : issuers;
		this.issuers = new ConcurrentHashMap<>(nextIssuers);
	}

	public static class Issuer {

		private String publicKeyUri;

		public String getPublicKeyUri() {
			return publicKeyUri;
		}

		public void setPublicKeyUri(String publicKeyUri) {
			this.publicKeyUri = publicKeyUri;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Issuer issuer)) {
				return false;
			}
			return Objects.equals(publicKeyUri, issuer.publicKeyUri);
		}

		@Override
		public int hashCode() {
			return Objects.hash(publicKeyUri);
		}
	}
}
