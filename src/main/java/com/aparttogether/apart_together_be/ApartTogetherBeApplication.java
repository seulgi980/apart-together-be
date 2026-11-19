package com.aparttogether.apart_together_be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApartTogetherBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApartTogetherBeApplication.class, args);
	}

}
