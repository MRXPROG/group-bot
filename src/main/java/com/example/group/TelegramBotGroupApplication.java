package com.example.group;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.example.notification.repository")
public class TelegramBotGroupApplication {

	public static void main(String[] args) {
		SpringApplication.run(TelegramBotGroupApplication.class, args);
	}

}
