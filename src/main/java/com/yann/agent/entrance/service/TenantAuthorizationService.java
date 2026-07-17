package com.yann.agent.entrance.service;

import com.yann.agent.entrance.support.JsonLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class TenantAuthorizationService {

	private static final Logger log = LoggerFactory.getLogger(TenantAuthorizationService.class);

	private final Set<String> authorizations = ConcurrentHashMap.newKeySet();

	public TenantAuthorizationService() {
		authorizations.add(key("portal", "dev-assistant"));
	}

	public void requireAuthorized(String tenantId, String agentId) {
		if (!authorizations.contains(key(tenantId, agentId))) {
			JsonLog.warn(log, "tenant.authorization.denied",
					"tenantId", tenantId,
					"agentId", agentId
			);
			throw new ResponseStatusException(FORBIDDEN, "AGENT_NOT_AUTHORIZED");
		}
		JsonLog.info(log, "tenant.authorization.allowed",
				"tenantId", tenantId,
				"agentId", agentId
		);
	}

	private String key(String tenantId, String agentId) {
		return tenantId + ":" + agentId;
	}
}

