package com.nordencapital.spine.push;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
class PushConfig {

	/**
	 * The real sender when the VAPID keys are set. Fails at startup on bad or half-set keys
	 * (better than silently sending nothing).
	 */
	@Bean
	PushSender pushSender(PushProperties properties, Clock clock) {
		VapidKeys keys = VapidKeys.from(properties);
		if (keys == null) {
			return (target, payload) -> {
				throw new IllegalStateException("push is off (no VAPID keys)");
			};
		}
		return new HttpPushSender(keys, clock);
	}

}
