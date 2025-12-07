package com.example.group;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.example.group.repository")
public class TelegramBotGroupApplication {

	public static void main(String[] args) {
		SpringApplication.run(TelegramBotGroupApplication.class, args);
	}

}
