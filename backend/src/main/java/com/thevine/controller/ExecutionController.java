package com.thevine.controller;

import com.thevine.parser.*;
import com.thevine.engine.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.*;

@RestController
@RequestMapping("/api/execute")
@CrossOrigin(origins = "http://localhost:3000")
public class ExecutionController {
    
    @Autowired
    private CodeParser parser;
    
    @Autowired
    private ExecutionEngine engine;
    
    @PostMapping
    public Map<String, Object> execute(@RequestBody Map<String, Object> request) {
        String code = (String) request.get("code");
        
        // Parse the code
        ParsedResult parsed = parser.parse(code);
        
        // Build execution context from request
        ExecutionContext context = new ExecutionContext();
        @SuppressWarnings("unchecked")
        Map<String, Object> contextMap = (Map<String, Object>) request.get("context");
        if (contextMap != null) {
            Object pythonVenv = contextMap.get("pythonVenvPath");
            if (pythonVenv != null) {
                context.setPythonVenvPath((String) pythonVenv);
            }
            
            Object npmModules = contextMap.get("npmNodeModulesPath");
            if (npmModules != null) {
                context.setNpmNodeModulesPath((String) npmModules);
            }
        }
        
        // Also accept projectPath at top level
        Object projectPath = request.get("projectPath");
        if (projectPath != null) {
            context.setEngineOption("projectPath", (String) projectPath);
        }
        
        // Execute with context configuration
        ExecutionResult result = engine.execute(parsed, context);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());

        // Build a single compact output string: normalize newlines and
        // convert comma-separated numbers into bracketed lists without spaces
        StringBuilder outBuilder = new StringBuilder();
        if (result.getResultsList() != null) {
            for (Map<String, Object> blk : result.getResultsList()) {
                String stdout = blk.get("stdout") == null ? "" : blk.get("stdout").toString();
                String stderr = blk.get("stderr") == null ? "" : blk.get("stderr").toString();
                String blockErr = blk.get("error") == null ? "" : blk.get("error").toString();

                if (!stdout.isEmpty()) {
                    if (outBuilder.length() > 0) outBuilder.append("\n");
                    outBuilder.append(stdout.trim());
                }
                if (!stderr.isEmpty()) {
                    if (outBuilder.length() > 0) outBuilder.append("\n");
                    outBuilder.append("[stderr] ").append(stderr.trim());
                }
                if (!blockErr.isEmpty()) {
                    if (outBuilder.length() > 0) outBuilder.append("\n");
                    outBuilder.append("[error] ").append(blockErr.trim());
                }
            }
        }

        // Return the raw combined output without aggressive regex mangling.
        // Normalizing CRLF to LF is sufficient for a terminal output.
        response.put("output", outBuilder.toString().replace("\r\n", "\n").replace("\r", "\n"));

        // Compact shared values: convert lists to compact bracketed strings
        Map<String, Object> compactShared = new HashMap<>();
        Map<String, Object> shared = result.getShared();
        if (shared != null) {
            for (Map.Entry<String, Object> e : shared.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List) {
                    // join elements with commas, no spaces
                    List<?> lst = (List<?>) v;
                    StringJoiner sj = new StringJoiner(",", "[", "]");
                    for (Object it : lst) {
                        sj.add(it == null ? "null" : it.toString());
                    }
                    compactShared.put(e.getKey(), sj.toString());
                } else {
                    compactShared.put(e.getKey(), v);
                }
            }
        }
        response.put("shared", compactShared);

        if (!result.isSuccess()) {
            response.put("error", result.getError());
        }

        return response;
    }
}