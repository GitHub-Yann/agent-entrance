package com.yann.agent.entrance.service;

import com.yann.agent.entrance.model.Agent;
import com.yann.agent.entrance.model.AgentAccessAudit;
import com.yann.agent.entrance.model.UserContext;
import com.yann.agent.entrance.support.JsonLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	private final Clock clock;
	private final List<AgentAccessAudit> audits = new CopyOnWriteArrayList<>();

	public AuditService(Clock clock) {
		this.clock = clock;
	}

	public void recordAccess(UserContext user, Agent agent) {
		AgentAccessAudit audit = new AgentAccessAudit(
				user.userId(),
				user.username(),
				user.tenantId(),
				user.tenantName(),
				agent.id(),
				agent.name(),
				clock.instant()
		);
		audits.add(audit);
		JsonLog.info(log, "agent.access.audit.recorded",
				"userId", audit.userId(),
				"tenantId", audit.tenantId(),
				"agentId", audit.agentId(),
				"accessTime", audit.accessTime()
		);
	}

	public List<AgentAccessAudit> list() {
		return List.copyOf(audits);
	}
}

