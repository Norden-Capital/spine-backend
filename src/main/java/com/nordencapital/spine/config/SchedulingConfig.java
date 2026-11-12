package com.nordencapital.spine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled jobs (for example the cleanup of old unattached uploads).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
