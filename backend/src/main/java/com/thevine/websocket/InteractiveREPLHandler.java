package com.thevine.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thevine.engine.ExecutionContext;
import com.thevine.parser.*;
import org.graalvm.polyglot.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class InteractiveREPLHandler extends TextWebSocketHandler {
    
    private static final ObjectMapper JSON = new ObjectMapper();
    
    // Store active sessions and their execution state
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    
    // Pending input requests per session
    private final Map<String, CompletableFuture<String>> inputFutures = new ConcurrentHashMap<>();
    
    private static class SessionState {
        String sessionId;
        Context polyglotContext;
        Map<String, Object> sharedMemory;
        Thread executionThread;
        boolean isRunning;
        
        SessionState(String sessionId) {
            this.sessionId = sessionId;
            this.sharedMemory = new HashMap<>();
            this.isRunning = false;
        }
    }
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, new SessionState(sessionId));
        System.out.println("✅ REPL session connected: " + sessionId);
        
        sendMessage(session, "connected", Map.of(
            "message", "REPL session ready",
            "sessionId", sessionId
        ));
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        SessionState state = sessions.get(sessionId);
        
        if (state == null) {
            sendError(session, "Session not found");
            return;
        }
        
        try {
            Map<String, Object> request = JSON.readValue(message.getPayload(), Map.class);
            String action = (String) request.get("action");
            
            switch (action) {
                case "execute":
                    handleExecute(session, state, request);
                    break;
                case "input":
                    handleInput(sessionId, request);
                    break;
                case "interrupt":
                    handleInterrupt(sessionId, state);
                    break;
                case "reset":
                    handleReset(sessionId, state);
                    break;
                default:
                    sendError(session, "Unknown action: " + action);
            }
        } catch (Exception e) {
            sendError(session, "Error: " + e.getMessage());
        }
    }
    
    private void handleExecute(WebSocketSession session, SessionState state, Map<String, Object> request) {
        if (state.isRunning) {
            sendError(session, "A program is already running. Use 'interrupt' to stop it.");
            return;
        }
        
        String code = (String) request.get("code");
        String language = (String) request.getOrDefault("language", "python");
        String venvPath = (String) request.get("venvPath");
        
        state.isRunning = true;
        
        state.executionThread = new Thread(() -> {
            try {
                executeCode(session, state, code, language, venvPath);
            } catch (Exception e) {
                sendError(session, "Execution error: " + e.getMessage());
            } finally {
                state.isRunning = false;
                sendMessage(session, "execution_complete", Map.of("sessionId", state.sessionId));
            }
        });
        state.executionThread.start();
    }
    
    private void executeCode(WebSocketSession session, SessionState state, String code, String language, String venvPath) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        
        Context.Builder builder = Context.newBuilder()
                .allowAllAccess(true)
                .out(outStream)
                .err(errStream);
        
        // Configure Python venv if provided (before building context)
        if ("python".equals(language) && venvPath != null && !venvPath.isEmpty()) {
            configurePythonContext(builder, venvPath);
        }
        
        try (Context ctx = builder.build()) {
            
            state.polyglotContext = ctx;
            
            // Inject shared variables
            if (!"java".equals(language)) {
                for (Map.Entry<String, Object> entry : state.sharedMemory.entrySet()) {
                    try {
                        ctx.getBindings(language).putMember(entry.getKey(), entry.getValue());
                    } catch (Exception ignored) {}
                }
            }
            
            // Process code - handle input() calls
            String processedCode = processCodeForInput(code, session.getId());
            
            // Execute
            Value result = ctx.eval(language, processedCode);
            
            // Capture output
            String output = outStream.toString();
            String errors = errStream.toString();
            
            // Send result
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("type", "output");
            resultMap.put("stdout", output);
            resultMap.put("stderr", errors);
            resultMap.put("result", result != null ? result.toString() : null);
            
            sendMessage(session, "execution_result", resultMap);
            
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg == null) {
                errorMsg = "Unknown error";
            }
            sendMessage(session, "error", Map.of("message", errorMsg));
        }
    }
    
    private String processCodeForInput(String code, String sessionId) {
        // Replace input() calls with a custom function that sends a prompt to the client
        // For GraalPy, we need to intercept input differently
        // This is a simplified approach - we detect input and handle it via stdin reader
        
        return code;
    }
    
    private void configurePythonContext(Context.Builder builder, String venvPath) {
        File venv = new File(venvPath);
        String os = System.getProperty("os.name").toLowerCase();
        
        String pythonExe;
        if (os.contains("win")) {
            pythonExe = new File(venv, "Scripts\\python.exe").getAbsolutePath();
        } else {
            pythonExe = new File(venv, "bin/python3").getAbsolutePath();
        }
        
        builder.option("python.Executable", pythonExe);
        System.out.println("🐍 REPL using Python: " + pythonExe);
    }
    
    private void handleInput(String sessionId, Map<String, Object> request) {
        String input = (String) request.get("value");
        CompletableFuture<String> future = inputFutures.remove(sessionId);
        if (future != null) {
            future.complete(input);
        }
    }
    
    private void handleInterrupt(String sessionId, SessionState state) {
        if (state.executionThread != null && state.isRunning) {
            state.executionThread.interrupt();
            sendMessage(state.sessionId, "interrupted", Map.of("message", "Execution interrupted"));
        }
    }
    
    private void handleReset(String sessionId, SessionState state) {
        state.sharedMemory.clear();
        sendMessage(sessionId, "reset", Map.of("message", "Session reset"));
    }
    
    private void sendMessage(WebSocketSession session, String type, Map<String, Object> data) {
        try {
            if (session.isOpen()) {
                Map<String, Object> message = new HashMap<>(data);
                message.put("type", type);
                message.put("timestamp", System.currentTimeMillis());
                session.sendMessage(new TextMessage(JSON.writeValueAsString(message)));
            }
        } catch (Exception e) {
            // Connection may have been closed by client - this is not necessarily an error
            if (!e.getMessage().contains("closed") && !e.getMessage().contains("aborted")) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
        }
    }
    
    private void sendMessage(String sessionId, String type, Map<String, Object> data) {
        sessions.values().stream()
                .filter(s -> s.sessionId.equals(sessionId))
                .findFirst()
                .ifPresent(s -> {
                    try {
                        Map<String, Object> message = new HashMap<>(data);
                        message.put("type", type);
                        message.put("timestamp", System.currentTimeMillis());
                    } catch (Exception e) {
                        // Ignore - session may be closed
                    }
                });
    }
    
    private void sendError(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                sendMessage(session, "error", Map.of("message", message));
            }
        } catch (Exception ignored) {}
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        SessionState state = sessions.remove(sessionId);
        if (state != null && state.executionThread != null) {
            state.executionThread.interrupt();
        }
        System.out.println("❌ REPL session disconnected: " + sessionId);
    }
}
