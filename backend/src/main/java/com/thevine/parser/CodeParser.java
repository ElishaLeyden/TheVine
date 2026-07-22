package com.thevine.parser;

import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class CodeParser {
    // Map common tag aliases to Graal engine ids
    private static final Map<String, String> LANG_ALIASES = Map.ofEntries(
        Map.entry("javascript", "js"),
        Map.entry("js", "js"),
        Map.entry("python", "python"),
        Map.entry("py", "python")
        ,Map.entry("ruby", "ruby")
        ,Map.entry("rb", "ruby")
        ,Map.entry("r", "r")
        ,Map.entry("llvm", "llvm")
        ,Map.entry("wasm", "wasm")
        ,Map.entry("java", "java")
        ,Map.entry("c", "c")
        ,Map.entry("cpp", "cpp")
        ,Map.entry("c++", "cpp")
    );
    
    // Pattern to find language blocks (@python, @javascript, etc.)
    // Accept CRLF or LF after the tag and capture until the next @tag or EOF
    private static final Pattern LANGUAGE_PATTERN = 
        // Treat @tag as a language block delimiter, but ignore @export and @import
        // so those lines are treated as content inside a block.
        Pattern.compile("@(?!export\\b|import\\b)(\\w+)\\s*\\r?\\n([\\s\\S]*?)(?=@(?!export\\b|import\\b)\\w+\\s*\\r?\\n|$)", Pattern.MULTILINE);
    
    // Export pattern: matches @export("name", value) where value may contain nested parentheses
    // e.g. @export("x", JSON.stringify(obj)) or @export("y", mapper.writeValueAsString(data))
    // Uses greedy match on a single line (no DOTALL) up to the last closing paren.
    private static final Pattern EXPORT_PATTERN = 
        Pattern.compile("^\\s*@export\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*(.+)\\)", Pattern.MULTILINE);

    private static final Pattern IMPORT_PATTERN = 
        Pattern.compile("\\s*@import\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)", Pattern.DOTALL);
    
    private static final Pattern TAG_LINE = Pattern.compile("^@(?!export\\b|import\\b)(\\w+)\\s*$");

    public ParsedResult parse(String code) {
        System.out.println("🔍 Parsing code...");

        if (code == null) {
            return new ParsedResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        List<CodeBlock> blocks = new ArrayList<>();
        List<Export> exports = new ArrayList<>();
        List<Import> imports = new ArrayList<>();

        String[] lines = code.split("\\r?\\n", -1);
        String currentLang = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            java.util.regex.Matcher tagMatch = TAG_LINE.matcher(trimmed);
            if (tagMatch.matches()) {
                if (currentLang != null) {
                    String content = currentContent.toString().trim();
                    System.out.println("Found " + currentLang + " block");
                    blocks.add(new CodeBlock(currentLang, content));
                    currentContent.setLength(0);
                }
                // normalize alias -> engine id
                String raw = tagMatch.group(1);
                String norm = LANG_ALIASES.getOrDefault(raw.toLowerCase(), raw.toLowerCase());
                currentLang = norm;
            } else {
                if (currentLang != null) {
                    currentContent.append(line).append("\n");
                }
            }
        }

        if (currentLang != null) {
            String content = currentContent.toString().trim();
            System.out.println("Found " + currentLang + " block");
            blocks.add(new CodeBlock(currentLang, content));
        }

        for (CodeBlock block : blocks) {
            String content = block.getCode();
            Matcher exportMatcher = EXPORT_PATTERN.matcher(content);
            while (exportMatcher.find()) {
                String name = exportMatcher.group(1);
                String value = exportMatcher.group(2);
                System.out.println("Found export in " + block.getLanguage() + ": name=" + name + " value=" + value);
                exports.add(new Export(name, value, block.getLanguage()));
            }
            Matcher importMatcher = IMPORT_PATTERN.matcher(content);
            while (importMatcher.find()) {
                String name = importMatcher.group(1);
                System.out.println("Found import in " + block.getLanguage() + ": name=" + name);
                imports.add(new Import(name, block.getLanguage()));
            }
        }

        return new ParsedResult(blocks, exports, imports);
    }
}
