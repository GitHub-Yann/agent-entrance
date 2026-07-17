package com.yann.agent.entrance.support;

import org.slf4j.MDC;

import java.util.function.Supplier;

public final class CorrelationContext {

	public static final String KEY = "correlation";

	private CorrelationContext() {
	}

	public static String current() {
		return MDC.get(KEY);
	}

	public static <T> T call(String correlation, Supplier<T> supplier) {
		String previous = MDC.get(KEY);
		if (correlation != null) {
			MDC.put(KEY, correlation);
		}
		try {
			return supplier.get();
		} finally {
			if (previous == null) {
				MDC.remove(KEY);
			} else {
				MDC.put(KEY, previous);
			}
		}
	}

	public static void run(String correlation, Runnable runnable) {
		call(correlation, () -> {
			runnable.run();
			return null;
		});
	}
}
