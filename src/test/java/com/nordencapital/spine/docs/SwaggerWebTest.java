package com.nordencapital.spine.docs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

import com.nordencapital.spine.config.SecurityConfig;
import com.nordencapital.spine.ping.PingController;

/**
 * Web layer only (ping controller, our security rules, springdoc), no database, so it
 * runs without Docker. The active profiles load the real application yml files.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest(controllers = PingController.class, properties = { "APP_CORS_ALLOWED_ORIGINS=https://example.com",
		"APP_FRONTEND_URL=https://example.com", "APP_MAIL_FROM=Spine <no-reply@example.com>",
		"RESEND_API_KEY=test-key", "APP_BOOTSTRAP_ADMIN_EMAIL=admin@example.com" })
@Import(SecurityConfig.class)
@ImportAutoConfiguration({ SpringDocConfiguration.class, SpringDocConfigProperties.class,
		SpringDocWebMvcConfiguration.class, SwaggerConfig.class, SwaggerUiConfigProperties.class,
		SwaggerUiOAuthProperties.class })
@interface SwaggerWebTest {

}
