package com.src.minidoodle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the meeting scheduling service. */
@SpringBootApplication
public class MiniDoodleApplication {

    /**
     * Starts the HTTP service and its configured persistence and monitoring components.
     *
     * @param args Spring Boot command-line configuration overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(MiniDoodleApplication.class, args);
    }
}
