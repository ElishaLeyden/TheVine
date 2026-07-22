package com.thevine.parser;

public class Import {
    private String name;
    private String language;
    
    public Import(String name, String language) {
        this.name = name;
        this.language = language;
    }
    
    public String getName() { return name; }
    public String getLanguage() { return language; }
}
