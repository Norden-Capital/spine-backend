package com.nordencapital.spine.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safe by default: with no profile at all, Swagger is off.
 */
@SwaggerWebTest
class SwaggerDefaultTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void apiDocsAndSwaggerUiAreNotFound() {
		assertThat(this.mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND);
	}

}
