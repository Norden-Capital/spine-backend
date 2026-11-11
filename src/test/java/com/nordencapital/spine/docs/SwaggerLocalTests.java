package com.nordencapital.spine.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@SwaggerWebTest
@ActiveProfiles("local")
class SwaggerLocalTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void apiDocsListPingEndpoint() {
		assertThat(this.mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.paths['/api/ping'].get")
			.isNotNull();
	}

	@Test
	void swaggerUiIsServed() {
		assertThat(this.mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.OK)
			.bodyText()
			.contains("swagger-ui");
	}

}
