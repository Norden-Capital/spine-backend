package com.nordencapital.spine.ping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(PingController.class)
class PingControllerTests {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void pingReturnsOkWithTime() {
		MvcTestResult result = this.mvc.get().uri("/api/ping").exchange();
		assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ok");
		assertThat(result).bodyJson().extractingPath("$.time").asString().isNotBlank();
	}

}
