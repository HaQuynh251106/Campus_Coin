package com.campuscoin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI campusCoinOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Campus Coin API")
                        .description("RESTful API documentation for Campus Coin - University Digital Wallet and Rewards System")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Campus Coin Dev Team")
                                .email("dev@campuscoin.edu.vn"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://springdoc.org")));
    }
}
