package com.thevine.engine;

public class BlockTiming {

    private String language;
    private double executionTimeMs;   // how long ctx.eval() took
    private long   memoryUsedMB;      // heap used after this block finished

    // Constructor
    public BlockTiming(String language, double executionTimeMs, long memoryUsedMB) {
        this.language        = language;
        this.executionTimeMs = executionTimeMs;
        this.memoryUsedMB    = memoryUsedMB;
    }

    // Getters (Jackson needs these to serialise to JSON)
    public String getLanguage()          { return language; }
    public double getExecutionTimeMs()   { return executionTimeMs; }
    public long   getMemoryUsedMB()      { return memoryUsedMB; }

    @Override
    public String toString() {
        return String.format("[%s] %.2f ms | heap: %d MB", language, executionTimeMs, memoryUsedMB);
    }
}