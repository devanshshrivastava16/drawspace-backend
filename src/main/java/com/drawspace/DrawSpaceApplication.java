package com.drawspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DrawSpace backend entry point.
 *
 * <p>{@link EnableScheduling} drives the 60s room/participant reaper (CONTRACT §10);
 * {@link ConfigurationPropertiesScan} registers {@code config.DrawSpaceProperties}.</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class DrawSpaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrawSpaceApplication.class, args);
    }
}
