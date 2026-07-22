package com.thevine.engine;

import java.util.Map;

/**
 * Represents language-specific execution context configuration.
 * Allows setting per-language options like Python virtual environments,
 * npm package paths, etc.
 */
public class ExecutionContext {
    private String pythonVenvPath;
    private String npmNodeModulesPath;
    private Map<String, String> engineOptions;

    public ExecutionContext() {
        this.engineOptions = new java.util.HashMap<>();
    }

    public String getPythonVenvPath() {
        return pythonVenvPath;
    }

    public void setPythonVenvPath(String pythonVenvPath) {
        this.pythonVenvPath = pythonVenvPath;
    }

    public String getNpmNodeModulesPath() {
        return npmNodeModulesPath;
    }

    public void setNpmNodeModulesPath(String npmNodeModulesPath) {
        this.npmNodeModulesPath = npmNodeModulesPath;
    }

    public Map<String, String> getEngineOptions() {
        return engineOptions;
    }

    public void setEngineOptions(Map<String, String> engineOptions) {
        this.engineOptions = engineOptions;
    }

    public void setEngineOption(String key, String value) {
        this.engineOptions.put(key, value);
    }

    public String getEngineOption(String key) {
        return this.engineOptions.get(key);
    }
}
