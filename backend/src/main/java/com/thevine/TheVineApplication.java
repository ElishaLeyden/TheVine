package com.thevine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@SpringBootApplication
@RestController
public class TheVineApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(TheVineApplication.class, args);
        System.out.println("🎉 TheVine Backend Started!");
        System.out.println("📍 http://localhost:8080");
    }
    
    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
            "name", "TheVine Backend",
            "status", "running",
            "health", "/api/health"
        );
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "running", "message", "TheVine is alive!");
    }
}
