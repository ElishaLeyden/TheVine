package com.thevine.parser;

public class CodeBlock {
    private String language;
    private String code;
    
    public CodeBlock(String language, String code) {
        this.language = language;
        this.code = code;
    }
    
    public String getLanguage() { return language; }
    public String getCode() { return code; }
}
