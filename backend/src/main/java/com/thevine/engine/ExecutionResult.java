package com.thevine.engine;

import java.util.*;

public class ExecutionResult {
    private boolean success;
    private List<Map<String, Object>> resultsList;
    private Map<String, Object> shared;
    private String error;
    private double totalExecutionTimeMs;
    private List<BlockTiming> blockTimings;

    public ExecutionResult() {
    }

    public ExecutionResult(boolean success, List<Map<String, Object>> resultsList,
                           Map<String, Object> shared, String error) {
        this.success = success;
        this.resultsList = resultsList;
        this.shared = shared;
        this.error = error;
    }

    public static ExecutionResult successList(
        List<Map<String, Object>> resultsList,
        Map<String, Object> shared,
        double totalExecutionTimeMs,
        List<BlockTiming> blockTimings) {

        ExecutionResult r = new ExecutionResult();
        r.success              = true;
        r.resultsList          = resultsList;
        r.shared               = shared;
        r.totalExecutionTimeMs = totalExecutionTimeMs;
        r.blockTimings         = blockTimings;
        return r;
    }

    public static ExecutionResult error(String language, String message) {
        return new ExecutionResult(false, null, null, language + ": " + message);
    }

    public boolean isSuccess() { return success; }
    public List<Map<String, Object>> getResultsList() { return resultsList; }
    public Map<String, Object> getShared() { return shared; }
    public String getError() { return error; }
    public double getTotalExecutionTimeMs() { return totalExecutionTimeMs; }
    public List<BlockTiming> getBlockTimings() { return blockTimings; }
}
