package com.nordencapital.spine.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@NoDatabaseSpringBootTest
@ActiveProfiles("prod")
class SwaggerProdTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void apiDocsAreNotFound() {
		assertThat(this.mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void swaggerUiIsNotFound() {
		assertThat(this.mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/swagger-ui.html")).hasStatus(HttpStatus.NOT_FOUND);
	}

}
