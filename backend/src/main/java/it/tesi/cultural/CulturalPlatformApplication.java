package it.tesi.cultural;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CulturalPlatformApplication {

    // Avvia il backend e il frontend statico con un'unica applicazione Spring.
    public static void main(String[] args) {
        SpringApplication.run(CulturalPlatformApplication.class, args);
    }
}
