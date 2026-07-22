package com.thevine.controller;

import com.thevine.parser.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;

@RestController
@RequestMapping("/api/parser")
public class ParserTestController {
    
    @Autowired
    private CodeParser parser;
    
    @PostMapping("/test")
    public Map<String, Object> testParse(@RequestBody Map<String, String> request) {
        String code = request.get("code");
        ParsedResult result = parser.parse(code);
        
        Map<String, Object> response = new HashMap<>();
        response.put("blocks", result.getBlocks().size());
        response.put("exports", result.getExports().size());
        response.put("imports", result.getImports().size());
        
        return response;
    }
}