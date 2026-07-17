package com.yann.agent.entrance.dto;

import com.yann.agent.entrance.config.SsoJwtProperties;

import java.util.HashMap;
import java.util.Map;

public class SsoJwtConfigResponse {

	private String cookieName;
	private Map<String, SsoJwtProperties.Issuer> issuers = new HashMap<>();

	public String getCookieName() {
		return cookieName;
	}

	public void setCookieName(String cookieName) {
		this.cookieName = cookieName;
	}

	public Map<String, SsoJwtProperties.Issuer> getIssuers() {
		return issuers;
	}

	public void setIssuers(Map<String, SsoJwtProperties.Issuer> issuers) {
		this.issuers = issuers == null ? new HashMap<>() : issuers;
	}
}
