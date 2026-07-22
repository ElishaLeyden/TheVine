package com.thevine.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/api/terminal")
@CrossOrigin(origins = "http://localhost:3000")
public class TerminalController {
    
    @PostMapping("/execute")
    public Map<String, Object> executeCommand(@RequestBody Map<String, String> request) {
        String command = request.get("command");
        String workingDir = request.get("workingDir");
        
        Map<String, Object> response = new HashMap<>();
        
        if (command == null || command.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "Command is required");
            return response;
        }
        
        try {
            ProcessBuilder pb = new ProcessBuilder();
            
            // Determine the shell based on OS
            String os = System.getProperty("os.name").toLowerCase();
            List<String> cmdList = new ArrayList<>();
            
            if (os.contains("win")) {
                // Windows: use cmd.exe with /c
                cmdList.add("cmd.exe");
                cmdList.add("/c");
                // Add echo of command for visibility
                cmdList.add(command + " 2>&1");
            } else {
                // Unix/Linux/Mac: use /bin/sh with -c
                cmdList.add("/bin/sh");
                cmdList.add("-c");
                cmdList.add(command + " 2>&1");
            }
            
            pb.command(cmdList);
            
            // Set working directory if provided
            if (workingDir != null && !workingDir.isEmpty()) {
                File dir = new File(workingDir);
                if (dir.exists() && dir.isDirectory()) {
                    pb.directory(dir);
                } else {
                    // Try to create parent directories
                    File parent = dir.getParentFile();
                    if (parent != null && parent.exists()) {
                        pb.directory(parent);
                        response.put("note", "Directory not found, using parent: " + parent.getAbsolutePath());
                    }
                }
            }
            
            // Set environment to include common paths
            Map<String, String> env = pb.environment();
            // Prepend Python and LLVM/Clang paths to ensure they are found
            String path = env.get("PATH");
            String pythonPaths = "C:\\Python312;C:\\Python311;C:\\Python310;C:\\Python39";
            String llvmPaths = "C:\\Program Files\\LLVM\\bin;C:\\Program Files (x86)\\LLVM\\bin";
            String newPaths = pythonPaths + ";" + llvmPaths;

            if (path != null && !path.isEmpty()) {
                // Prepend newPaths to existing PATH
                env.put("PATH", newPaths + ";" + path);
            } else {
                env.put("PATH", newPaths);
            }
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output with timeout
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                // Wait up to 60 seconds for pip installs
                long startTime = System.currentTimeMillis();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    // Check timeout for long operations
                    if (System.currentTimeMillis() - startTime > 60000) {
                        process.destroyForcibly();
                        output.append("\n[Process timed out after 60 seconds]");
                        break;
                    }
                }
            }
            
            int exitCode = process.waitFor();
            
            response.put("success", exitCode == 0);
            response.put("exitCode", exitCode);
            response.put("output", output.toString());
            response.put("workingDir", workingDir);
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Exception: " + e.getMessage());
            e.printStackTrace();
        }
        
        return response;
    }
    
    @GetMapping("/info")
    public Map<String, Object> getTerminalInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("os", System.getProperty("os.name"));
        info.put("home", System.getProperty("user.home"));
        info.put("cwd", System.getProperty("user.dir"));
        info.put("python", findPython());
        info.put("pip", findPip());
        return info;
    }
    
    private String findPython() {
        String[] paths = {
            "python",
            "python3",
            "py",
            "C:\\Python312\\python.exe",
            "C:\\Python311\\python.exe",
            "C:\\Python310\\python.exe",
            "C:\\Python39\\python.exe"
        };
        
        for (String p : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(p, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String version = r.readLine();
                    if (version != null && process.waitFor() == 0) {
                        return p + " - " + version;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "python not found";
    }
    
    private String findPip() {
        String[] paths = {
            "pip",
            "pip3",
            "C:\\Python312\\Scripts\\pip.exe",
            "C:\\Python311\\Scripts\\pip.exe",
            "C:\\Python310\\Scripts\\pip.exe",
            "C:\\Python39\\Scripts\\pip.exe"
        };
        
        for (String p : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(p, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String version = r.readLine();
                    if (version != null && process.waitFor() == 0) {
                        return p + " - " + version;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "pip not found";
    }
    
    private String findClang() {
        String[] paths = {
            "clang",
            "clang.exe",
            "C:\\Program Files\\LLVM\\bin\\clang.exe",
            "C:\\Program Files (x86)\\LLVM\\bin\\clang.exe",
            "C:\\LLVM\\bin\\clang.exe"
        };
        
        for (String p : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(p, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String version = r.readLine();
                    if (version != null && process.waitFor() == 0) {
                        return p + " - " + version;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "clang not found";
    }
}
