package com.thevine.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    private final InteractiveREPLHandler interactiveREPLHandler;
    
    public WebSocketConfig(InteractiveREPLHandler interactiveREPLHandler) {
        this.interactiveREPLHandler = interactiveREPLHandler;
    }
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(interactiveREPLHandler, "/ws/repl")
                .setAllowedOrigins("http://localhost:3000", "http://localhost:5173");
    }
}
