package com.nordencapital.spine;

import org.springframework.boot.SpringApplication;

public class TestSpineApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
