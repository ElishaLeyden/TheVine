package com.thevine.parser;

import java.util.*;

public class ParsedResult {
    private List<CodeBlock> blocks;
    private List<Export> exports;
    private List<Import> imports;
    
    public ParsedResult(List<CodeBlock> blocks, List<Export> exports, List<Import> imports) {
        this.blocks = blocks;
        this.exports = exports;
        this.imports = imports;
    }
    
    public List<CodeBlock> getBlocks() { return blocks; }
    public List<Export> getExports() { return exports; }
    public List<Import> getImports() { return imports; }
}
