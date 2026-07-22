package com.thevine.parser;

public class Export {
    private String name;
    private String value;
    private String language;
    
    public Export(String name, String value, String language) {
        this.name = name;
        this.value = value;
        this.language = language;
    }
    
    public String getName() { return name; }
    public String getValue() { return value; }
    public String getLanguage() { return language; }
}
