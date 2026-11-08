package com.bramhastra.baton;

import org.springframework.boot.SpringApplication;

public class TestBatonApplication {

	public static void main(String[] args) {
		SpringApplication.from(BatonApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
