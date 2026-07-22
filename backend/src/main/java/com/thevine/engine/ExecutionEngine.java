package com.thevine.engine;

import org.graalvm.polyglot.*;
import com.thevine.parser.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import com.thevine.engine.BlockTiming;
import java.util.ArrayList;

@Service
public class ExecutionEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    
    // Python environment auto-detection
    private static final String ENV_PYTHON_VENV = "THEVINE_PYTHON_VENV";
    private static final String DEFAULT_VENV_NAME = "venv";
    
    public ExecutionEngine() {
        System.out.println("âœ… TheVine Polyglot Engine Ready");
    }
    
    /**
     * Locates Python virtual environment.
     * Priority: 1) context path, 2) environment variable, 3) project-relative venv
     */
    private String findPythonVenv(String projectPath, ExecutionContext context) {
        // 1. Check context-provided path
        if (context != null && context.getPythonVenvPath() != null && !context.getPythonVenvPath().isEmpty()) {
            File ctxPath = new File(context.getPythonVenvPath());
            if (ctxPath.exists()) {
                System.out.println("ðŸ“ Using venv from context: " + context.getPythonVenvPath());
                return context.getPythonVenvPath();
            }
        }
        
        // 2. Check environment variable
        String envPath = System.getenv(ENV_PYTHON_VENV);
        if (envPath != null && !envPath.isEmpty()) {
            File envFile = new File(envPath);
            if (envFile.exists()) {
                System.out.println("ðŸ“ Using venv from env: " + envPath);
                return envPath;
            }
        }
        
        // 3. Look for venv in project directory
        if (projectPath != null && !projectPath.isEmpty()) {
            File projectDir = new File(projectPath);
            File venvDir = new File(projectDir, DEFAULT_VENV_NAME);
            if (venvDir.exists()) {
                System.out.println("ðŸ“ Using venv from project: " + venvDir.getAbsolutePath());
                return venvDir.getAbsolutePath();
            }
        }
        
        // 4. Check current working directory
        File cwdVenv = new File(DEFAULT_VENV_NAME);
        if (cwdVenv.exists()) {
            System.out.println("ðŸ“ Using venv from cwd: " + cwdVenv.getAbsolutePath());
            return cwdVenv.getAbsolutePath();
        }
        
        System.out.println("âš ï¸ No Python venv found, using system Python");
        return null;
    }
    
    /**
     * Configure GraalPy context with Python venv settings
     */
    private void configurePythonContext(Context.Builder builder, String venvPath) {
        if (venvPath == null) {
            return;
        }
        
        File venv = new File(venvPath);
        String os = System.getProperty("os.name").toLowerCase();
        
        // Determine paths based on OS
        String pythonExe;
        String sitePackages;
        String pythonHome;
        
        if (os.contains("win")) {
            // Windows
            pythonExe = new File(venv, "Scripts\\python.exe").getAbsolutePath();
            sitePackages = new File(venv, "Lib\\site-packages").getAbsolutePath();
            pythonHome = venv.getAbsolutePath();
        } else {
            // Unix/Linux/Mac
            pythonExe = new File(venv, "bin/python3").getAbsolutePath();
            
            // Resolve wildcard for python version (e.g., lib/python3.10/site-packages)
            File libDir = new File(venv, "lib");
            String resolvedSitePackages = null;
            if (libDir.exists() && libDir.isDirectory()) {
                File[] pythonDirs = libDir.listFiles((dir, name) -> name.startsWith("python"));
                if (pythonDirs != null && pythonDirs.length > 0) {
                    resolvedSitePackages = new File(pythonDirs[0], "site-packages").getAbsolutePath();
                }
            }
            if (resolvedSitePackages == null) {
                resolvedSitePackages = new File(venv, "lib/site-packages").getAbsolutePath();
            }
            sitePackages = resolvedSitePackages;
            pythonHome = venv.getAbsolutePath();
        }
        
        File pythonFile = new File(pythonExe);
        if (!pythonFile.exists()) {
            System.out.println("âš ï¸ Python executable not found: " + pythonExe);
            // Try alternative paths
            if (os.contains("win")) {
                pythonExe = new File(venv, "bin/python.exe").getAbsolutePath();
            } else {
                pythonExe = new File(venv, "bin/python").getAbsolutePath();
            }
        }
        
        System.out.println("ðŸ Configuring GraalPy with:");
        System.out.println("   Executable: " + pythonExe);
        System.out.println("   Site-packages: " + sitePackages);
        
        // Set GraalPy options
        builder.option("python.Executable", pythonExe);
        
        // Also set import paths for pure Python packages
        File sitePkgDir = new File(sitePackages);
        if (sitePkgDir.exists()) {
            builder.option("python.PythonPath", sitePackages);
        }
    }
    
    /**
     * Configure GraalVM Espresso with necessary system properties
     */
    private void configureJavaContext(Context.Builder builder) {
        String javaHome = System.getProperty("java.home");
        System.out.println("â˜• Configuring Espresso with Java Home: " + javaHome);
        String classpath = System.getProperty("java.class.path");
        builder.option("java.JavaHome", javaHome);
        builder.option("java.Classpath", classpath);
    }

    public ExecutionResult execute(ParsedResult parsed) {
        return execute(parsed, null);
    }

    public ExecutionResult execute(ParsedResult parsed, ExecutionContext context) {
        System.out.println("ðŸš€ Starting execution...");

        // Default context if none provided
        if (context == null) {
            context = new ExecutionContext();
        }

        // Move sharedMemory to local scope to ensure thread safety for concurrent users
        Map<String, Object> sharedMemory = new LinkedHashMap<>();
        String jacksonClasspath = System.getProperty("java.class.path");

        // Store project path in shared memory for file operations
        String projectPath = context.getEngineOption("projectPath");
        if (projectPath != null) {
            sharedMemory.put("_projectPath", projectPath);
        }

        List<Map<String, Object>> resultsList = new ArrayList<>();
        // ── MEASUREMENT: start total execution timer ──────────────────
        long totalStartTime = System.nanoTime();
        List<BlockTiming> blockTimings = new ArrayList<>();
// ─────────────────────────────────────────────────────────────

        // Execute each block in order with an isolated Context per block.
        // This avoids Java+LLVM interop clashes in a single context while
        // preserving cross-block data flow through sharedMemory.
        for (int i = 0; i < parsed.getBlocks().size(); i++) {
            CodeBlock block = parsed.getBlocks().get(i);
            System.out.println("Executing block[" + i + "] " + block.getLanguage());

            Map<String, Object> blockResult = new HashMap<>();
            blockResult.put("index", i);
            blockResult.put("language", block.getLanguage());

            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();
            // ── STEP 4b: Start the per-block stopwatch ───────────────────
            long blockStartTime = System.nanoTime();
// ─────────────────────────────────────────────────────────────
            try {
            // Use a dedicated Engine per block to isolate symbol tables.
            // This prevents "mixing native/ffi symbols with llvm signatures" errors
            // which occur when Espresso and user LLVM/Native code share an Engine.
            try (Engine engine = Engine.newBuilder().build()) {
                Context.Builder contextBuilder = Context.newBuilder()
                        .engine(engine)
                        .allowAllAccess(true)
                        .out(outStream)
                        .err(errStream);

                // Configure GraalPy with virtual environment if specified
                if ("python".equals(block.getLanguage())) {
                    // Re-use outer projectPath or check shared memory
                    if (projectPath == null) {
                        projectPath = (String) sharedMemory.get("_projectPath");
                    }
                    
                    String venvPath = findPythonVenv(projectPath, context);
                    if (venvPath != null) {
                        configurePythonContext(contextBuilder, venvPath);
                    } else {
                        System.out.println("âš ï¸ No Python venv found, using system Python");
                    }
                }
                
                // Configure Espresso options if the block is Java
                if ("java".equals(block.getLanguage())) {
                    configureJavaContext(contextBuilder);
                }

                try (Context ctx = contextBuilder.build()) {
                    try {
                    // Check language availability
                    String lang = block.getLanguage();
                    boolean isJava = "java".equals(lang);
                    boolean isEspresso = isJava && ctx.getEngine().getLanguages().containsKey("java");
                    boolean isCFamily = "c".equals(lang) || "cpp".equals(lang);

                    if (!isCFamily && !isJava && !ctx.getEngine().getLanguages().containsKey(lang)) {
                        throw new RuntimeException("Language not available: " + lang);
                    }

                    // Inject shared variables into the language bindings if present.
                    // Some engines may not support bindings or putMember; tolerate failures.
                    if (!isJava || isEspresso) {
                        for (Map.Entry<String, Object> entry : sharedMemory.entrySet()) {
                            try {
                                ctx.getBindings(lang).putMember(entry.getKey(), entry.getValue());
                            } catch (Exception ex) {
                                // ignore: engine may not support bindings for this language
                            }
                        }
                    }

                    // Prepare code for execution: remove directive lines like @export and @import
                    String[] codeLines = block.getCode().split("\r?\n");
                    StringBuilder execBuilder = new StringBuilder();
                    for (String l : codeLines) {
                        String t = l.trim();
                        if (t.startsWith("@export") || (t.startsWith("@import") && !t.contains("<"))) {
                            // skip directive lines
                            continue;
                        }
                        execBuilder.append(l).append("\n");
                    }
                    String execCode = execBuilder.toString();

                    // Replace inline @import("name") occurrences.
                    // Try to load from JSON file if name looks like a file or if simple name matches {name}.json
                    String processedCode = execCode;
                    Set<String> unresolvedImports = new LinkedHashSet<>();
                    Set<String> blockImportNames = extractImportNames(execCode);
                    
                    // --- C/C++ locals-to-globals pre-compilation transform ---
                    // Sulong can only read module-level LLVM globals from Java.  Variables
                    // declared inside main() or any function body are invisible as module-level
                    // symbols.  Before compiling, scan the code for exported identifiers and
                    // inject a file-scope forward declaration plus an assignment in main() so
                    // the value becomes a visible module-level LLVM global after main() runs.
                    java.util.Set<String> exportedInThisBlock = new java.util.LinkedHashSet<>();
                    if (isCFamily) {
                        for (Export exp : parsed.getExports()) {
                            if (exp.getLanguage().equals(lang)) {
                                exportedInThisBlock.add(exp.getValue());
                            }
                        }
                    }
                    if (isCFamily && !exportedInThisBlock.isEmpty()) {
                        processedCode = injectCFamilyExportGlobals(processedCode, exportedInThisBlock, "cpp".equals(lang));
                    }
                    // --- End C/C++ transform ---
                    
                    // Save pre-import-replacement code for building native fallback later.
                    // We use execCode (no Sulung transforms) as the base for native fallback.
                    String nativeFallbackBase = execCode;

                    for (String name : blockImportNames) {
                        boolean hasSharedValue = sharedMemory.containsKey(name);
                        Object imported = sharedMemory.get(name);

                        // If not in shared memory, check if it's a JSON file reference
                        if (!hasSharedValue || imported == null) {
                            imported = tryLoadFromJsonFile(name, sharedMemory);
                            if (imported != null) {
                                hasSharedValue = true;
                            }
                        }
                        
                        if (isCFamily && (!hasSharedValue || imported == null)) {
                            unresolvedImports.add(name);
                        }
                        String replacement = toLanguageLiteral(imported, lang, name);
                        // Use Pattern + Matcher for safe replacement that preserves backslashes
                        // in both the surrounding code AND the replacement string.
                        // replaceAll + quoteReplacement can still corrupt backslash-heavy
                        // content (C/C++ string literals with \"), so we use a manual loop.
                        String importRegex = "@import\\s*\\(\\s*[\"']" + Pattern.quote(name) + "[\"']\\s*\\)";
                        Pattern importPat = Pattern.compile(importRegex);
                        processedCode = safeReplaceAll(processedCode, importPat, replacement);
                        nativeFallbackBase = safeReplaceAll(nativeFallbackBase, importPat, replacement);
                    }

                    if (isCFamily && !unresolvedImports.isEmpty()) {
                        List<String> details = new ArrayList<>();
                        for (String missing : unresolvedImports) {
                            List<Integer> lines = findImportLines(execCode, missing);
                            if (lines.isEmpty()) {
                                details.add(missing);
                            } else {
                                details.add(missing + " at line(s) " + joinLineNumbers(lines));
                            }
                        }
                        throw new RuntimeException(
                            "Unresolved @import values for C/C++ block: " + String.join(", ", details)
                                + ". Ensure these names are exported by previous blocks before this C/C++ block runs."
                        );
                    }

                    // Execute
                    Value result = null;
                    Value llvmModule = null;
                    String nativeStdout = null;
                    String nativeStderr = null;
                    Integer nativeExitCode = null;
                    if (isCFamily) {
                        boolean usedNativeFallback = false;
                        if (!ctx.getEngine().getLanguages().containsKey("llvm")) {
                            // No LLVM engine available, go straight to native fallback
                            usedNativeFallback = true;
                        }
                        if (!usedNativeFallback) {
                            try {
                                Path bitcode = compileToLlvmBitcode(processedCode, "cpp".equals(lang));
                                Source llvmSource = Source.newBuilder("llvm", bitcode.toFile()).build();
                                llvmModule = ctx.eval(llvmSource);
                                // Sulong loads the module on eval; invoke main() explicitly if present.
                                if (llvmModule != null && llvmModule.hasMembers() && llvmModule.hasMember("main")) {
                                    Value mainFn = llvmModule.getMember("main");
                                    if (mainFn != null && mainFn.canExecute()) {
                                        result = mainFn.execute();
                                    } else {
                                        result = llvmModule;
                                    }
                                } else {
                                    result = llvmModule;
                                }
                            } catch (Exception cFamilyErr) {
                                // Fall back to native compilation for any error:
                                // PolyglotException (varargs, unsupported opcodes),
                                // RuntimeException (bitcode compilation failure),
                                // or any other compilation/execution issue.
                                System.err.println("⚡ LLVM/Sulung path failed for " + lang + ", falling back to native: " + cFamilyErr.getMessage());
                                usedNativeFallback = true;
                            }
                        }
                        if (usedNativeFallback) {
                            // Inject export printf statements for native C/C++ fallback
                            // Use nativeFallbackBase (no Sulung transforms) for clean native compilation
                            String nativeCode = injectCFamilyNativeExports(nativeFallbackBase, parsed.getExports(), lang, "cpp".equals(lang));
                            NativeRunResult nativeResult = compileAndRunNative(nativeCode, "cpp".equals(lang));
                            nativeStdout = nativeResult.stdout;
                            nativeStderr = nativeResult.stderr;
                            nativeExitCode = nativeResult.exitCode;
                            result = null;
                        }
                    } else if (isJava) {
                        if (isEspresso) {
                            // Execute via GraalVM Espresso (Java on Truffle)
                            // Espresso cannot eval raw source; compile to bytecode first,
                            // then load the class through Espresso's guest JVM.
                            NativeRunResult espressoResult = compileAndRunViaEspresso(ctx, processedCode, parsed.getExports(), jacksonClasspath);
                            nativeStdout = espressoResult.stdout;
                            nativeStderr = espressoResult.stderr;
                            nativeExitCode = espressoResult.exitCode;
                            result = null;
                        } else {
                            // Fallback to native compilation if Espresso is not installed
                            NativeRunResult nativeResult = compileAndRunNativeJava(processedCode, parsed.getExports(), jacksonClasspath);
                            nativeStdout = nativeResult.stdout;
                            nativeStderr = nativeResult.stderr;
                            nativeExitCode = nativeResult.exitCode;
                            result = null;
                        }
                    } else {
                        result = ctx.eval(lang, processedCode);
                    }

                    // Capture printed output
                    String stdout = outStream.toString();
                    String stderr = errStream.toString();
                    outStream.reset();
                    errStream.reset();

                    if (nativeStdout != null) {
                        stdout = (stdout == null ? "" : stdout) + nativeStdout;
                    }
                    if (nativeStderr != null) {
                        stderr = (stderr == null ? "" : stderr) + nativeStderr;
                    }

                    if (nativeExitCode != null) {
                        blockResult.put("value", String.valueOf(nativeExitCode));
                    } else {
                        blockResult.put("value", result == null ? null : result.toString());
                    }
                    blockResult.put("stdout", stdout);
                    blockResult.put("stderr", stderr);

                    // Native C/C++ stdout may bypass polyglot output capture on some runtimes.
                    // Provide a fallback message with the exit code so output isn't blank.
                    if (isCFamily && (stdout == null || stdout.isBlank())) {
                        String exitText = nativeExitCode == null ? deriveExitCodeText(result) : String.valueOf(nativeExitCode);
                        if (exitText != null && !exitText.isBlank()) {
                            blockResult.put("stdout", "[c/cpp] Program exited with code " + exitText);
                        }
                    }

                    // Check if any imports refer to JSON files.
                    // Supports explicit file paths (ending with .json or containing path separators)
                    // Also treats simple names as potential JSON files by trying {name}.json
                    for (Import imp : parsed.getImports()) {
                        if (!sharedMemory.containsKey(imp.getName())) {
                            tryLoadFromJsonFile(imp.getName(), sharedMemory);
                        }
                    }

                    // Process exports: evaluate the exported expression in the
                    // current language context so we store the actual value
                    List<Map<String, Object>> exported = new ArrayList<>();
                    if (isJava || (isCFamily && nativeStdout != null)) {
                        // Java and native C/C++ exports are handled by parsing
                        // THEVINE_EXPORT markers from stdout produced by the compiled program.
                        if (nativeStdout != null && !nativeStdout.isBlank()) {
                            Pattern exportPattern = Pattern.compile("THEVINE_EXPORT_([a-zA-Z0-9_]+):(.*)");
                            Matcher exportMatcher = exportPattern.matcher(nativeStdout);
                            while (exportMatcher.find()) {
                                String exportName = exportMatcher.group(1);
                                String jsonValue = exportMatcher.group(2);
                                try {
                                    Object exportedObject = JSON.readValue(jsonValue, Object.class);
                                    sharedMemory.put(exportName, exportedObject);
                                    Map<String, Object> e = new HashMap<>();
                                    e.put("name", exportName);
                                    e.put("value", exportedObject);
                                    exported.add(e);
                                } catch (Exception e) {
                                    System.err.println("Failed to parse exported value for '" + exportName + "': " + e.getMessage());
                                    // Fallback to raw string (strip surrounding quotes if present)
                                    String raw = jsonValue.trim();
                                    if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                        raw = raw.substring(1, raw.length() - 1);
                                    }
                                    sharedMemory.put(exportName, raw);
                                    Map<String, Object> e1 = new HashMap<>();
                                    e1.put("name", exportName);
                                    e1.put("value", raw);
                                    exported.add(e1);
                                }
                            }
                            // Strip THEVINE_EXPORT lines from user-visible stdout
                            String cleanedStdout = nativeStdout.replaceAll("THEVINE_EXPORT_[a-zA-Z0-9_]+:.*\\r?\\n?", "");
                            stdout = (stdout == null ? "" : stdout.replace(nativeStdout, "")) + cleanedStdout;
                            blockResult.put("stdout", stdout);
                        }
                    } else { // Polyglot languages (including Espresso Java) and C/C++
                        for (Export exp : parsed.getExports()) {
                            if (exp.getLanguage().equals(lang)) {
                                String expr = exp.getValue();
                                Object stored;
                                
                                // Dynamic C/C++ Data Exchange: 
                                // Check if the exported name exists as a global variable in the LLVM module
                                if (isCFamily && llvmModule != null) {
                                    String globalName = "__thevine_exp_" + expr;
                                    if (llvmModule.hasMember(globalName)) {
                                        stored = convertValue(llvmModule.getMember(globalName));
                                    } else if (llvmModule.hasMember(expr)) {
                                        stored = convertValue(llvmModule.getMember(expr));
                                    } else {
                                        // Fallback: try evaluating simple identifiers via the llvm language.
                                        // This helps when the exported value is a local variable inside main()
                                        // and therefore is not visible as an LLVM module member.
                                        String e = expr == null ? null : expr.trim();
                                        boolean looksLikeIdentifier = e != null && e.matches("^[A-Za-z_][A-Za-z0-9_]*$");
                                        stored = null;
                                        if (looksLikeIdentifier) {
                                            try {
                                                Value v = ctx.eval("llvm", e);
                                                stored = (v != null) ? convertValue(v) : null;
                                            } catch (Exception evalEx) {
                                                stored = null;
                                            }
                                        }
                                        if (stored == null) {
                                            stored = parseExportLiteral(expr);
                                        }
                                    }
                                } else {
                                    stored = evaluatePolyglotExport(ctx, lang, expr);
                                }
                                
                                // Provide feedback if an intended C/C++ export failed to find the symbol
                                if (isCFamily && stored == null && !expr.startsWith("\"") && !expr.startsWith("{")) {
                                    System.err.println("âš ï¸ C/C++ Export Warning: Symbol '" + expr + "' not found in LLVM module. " +
                                        "Ensure it is a GLOBAL variable. In C++, also wrap it in 'extern \"C\"' to avoid mangling.");
                                }
                                
                                if (stored != null) {
                                    sharedMemory.put(exp.getName(), stored);
                                    Map<String, Object> exportInfo = new HashMap<>();
                                    exportInfo.put("name", exp.getName());
                                    exportInfo.put("value", stored);
                                    exported.add(exportInfo);
                                }
                            }
                        }
                    }
                    blockResult.put("exports", exported);

                    // Auto-export complex objects to JSON files
                    // If exported value is a Map or List, also save it as {name}.json in the project directory
                    for (Export exp : parsed.getExports()) {
                        if (exp.getLanguage().equals(lang) && sharedMemory.containsKey(exp.getName())) {
                            Object exportedValue = sharedMemory.get(exp.getName());
                            // Check if it's a complex object (Map or List)
                            if (exportedValue instanceof Map<?, ?> || exportedValue instanceof List<?>) {
                                try {
                                    String pPath = (String) sharedMemory.get("_projectPath");
                                    if (pPath != null) {
                                        String fileName = exp.getName();
                                        if (!fileName.endsWith(".json")) {
                                            fileName = fileName + ".json";
                                        }
                                        Path filePath = Path.of(pPath, fileName);
                                        Files.createDirectories(filePath.getParent());
                                        String jsonContent = JSON.writeValueAsString(exportedValue);
                                        Files.writeString(filePath, jsonContent, StandardCharsets.UTF_8);
                                        System.out.println("ðŸ’¾ Exported object '" + exp.getName() + "' to " + fileName);
                                    }
                                } catch (IOException e) {
                                    System.out.println("âš ï¸ Failed to export object to file: " + e.getMessage());
                                }
                            }
                        }
                    }
                    // ── STEP 4c: Stop the per-block stopwatch and take measurements ──

                    // 1. Stop the stopwatch for this block
                    long blockEndTime = System.nanoTime();

                    // 2. Calculate how many milliseconds this block took
                    //    nanoTime() gives nanoseconds, so divide by 1,000,000 to get milliseconds
                    //    We use double (not int) so we keep the decimal places e.g. 234.56 ms
                    double blockMs = (blockEndTime - blockStartTime) / 1_000_000.0;

                    // 3. Ask the JVM how much memory (heap) is currently being used
                    //    totalMemory() = total memory the JVM has reserved
                    //    freeMemory()  = how much of that reserved memory is currently unused
                    //    used = total - free
                    Runtime rt = Runtime.getRuntime();
                    long usedBytes = rt.totalMemory() - rt.freeMemory();
                    long usedMB    = usedBytes / (1024 * 1024);  // convert bytes → megabytes

                    // 4. Create one BlockTiming object for this block
                    BlockTiming timing = new BlockTiming(block.getLanguage(), blockMs, usedMB);

                    // 5. Add it to the list
                    blockTimings.add(timing);

                    // 6. Print to the Spring Boot console so you can see it live while testing
                    System.out.printf("[THEVINE TIMING] Block [%-12s] → %.2f ms | heap: %d MB%n",
                        block.getLanguage(), blockMs, usedMB);

                    // ────────────────────────────────────────────────────────────────

                } catch (PolyglotException pe) {
                    String err = pe.getMessage();
                    if (err != null && err.contains("missing LLVM builtin: llvm.va_start")) {
                        err = "C/C++ varargs are not supported by the current LLVM runtime in this setup (triggered by printf/scanf-style calls). "
                            + "Use non-varargs output (e.g., puts), return values, or run C/C++ via native toolchain execution instead of Sulong for full libc support.";
                    } else if (err != null && err.contains("Unsupported opCode in constant block")) {
                        err = "This C++ bitcode is not compatible with the current Graal LLVM runtime (often triggered by modern C++ stdlib like iostream or newer LLVM bitcode format). "
                            + "Use simpler C/C++ without iostream/complex stdlib in LLVM mode, or use native compile+run for full C++ support.";
                    }
                    blockResult.put("error", enrichPolyglotErrorWithLine(pe, err));
                } catch (Error er) {
                    blockResult.put("error", enrichTextErrorWithLine(er.getMessage() == null ? er.toString() : er.getMessage()));
                } catch (Exception e) {
                    blockResult.put("error", enrichTextErrorWithLine(e.getMessage()));
                }
                } // Closes try (Context ctx)
            } // Closes try (Engine engine)
            } catch (Throwable t) {
                blockResult.put("error", t.getMessage());
            } finally {
                resultsList.add(blockResult);
            }
        }
        // ── STEP 4d: Stop the total stopwatch ────────────────────────

        // Stop the total stopwatch
        long totalEndTime = System.nanoTime();

        // Calculate total time in milliseconds
        double totalMs = (totalEndTime - totalStartTime) / 1_000_000.0;

        // Take one final memory reading
        long finalMemMB = (Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        // Print the summary to the console
        System.out.printf("%n[THEVINE TIMING] ═══════════════════════════════════%n");
        System.out.printf("[THEVINE TIMING]  TOTAL execution time : %.2f ms%n", totalMs);
        System.out.printf("[THEVINE TIMING]  Final heap used      : %d MB%n", finalMemMB);
        System.out.printf("[THEVINE TIMING] ═══════════════════════════════════%n%n");

        // ─────────────────────────────────────────────────────────────
       return ExecutionResult.successList(resultsList, sharedMemory, totalMs, blockTimings);
    }

    /**
     * Evaluates an export expression in a polyglot context and converts the result.
     * @param ctx The polyglot context.
     * @param lang The language of the expression.
     * @param expr The expression string.
     * @return The converted Java object, or the raw expression string if evaluation fails.
     */
    private Object evaluatePolyglotExport(Context ctx, String lang, String expr) {
        try {
            Value v = ctx.eval(lang, expr);
            return (v != null) ? convertValue(v) : null;
        } catch (PolyglotException pe2) {
            // If evaluation fails, fall back to storing the raw text
            System.err.println("Polyglot export evaluation failed for expression '" + expr + "': " + pe2.getMessage());
            return expr;
        }
    }

    // Convert a Graal Value into plain Java objects (primitives, List, Map)
    private Object convertValue(Value v) {
        if (v == null || v.isNull()) return null;
        try {
            if (v.isHostObject()) return v.asHostObject();
            if (v.isBoolean()) return v.asBoolean();
            if (v.isNumber()) {
                if (v.fitsInInt()) return v.asInt();
                if (v.fitsInLong()) return v.asLong();
                return v.asDouble();
            }
            if (v.isString()) return v.asString();

            if (v.hasArrayElements()) {
                long len = v.getArraySize();
                List<Object> list = new ArrayList<>();
                for (long i = 0; i < len; i++) {
                    Value e = v.getArrayElement(i);
                    list.add(convertValue(e));
                }
                return list;
            }

            if (v.hasHashEntries()) {
                Map<String, Object> map = new LinkedHashMap<>();
                Value iterator = v.getHashEntriesIterator();
                while (iterator.hasIteratorNextElement()) {
                    Value entry = iterator.getIteratorNextElement();
                    Value key = entry.getArrayElement(0);
                    Value val = entry.getArrayElement(1);
                    String keyStr = key.isString() ? key.asString() : key.toString();
                    map.put(keyStr, convertValue(val));
                }
                return map;
            }

            if (v.hasMembers()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (String key : v.getMemberKeys()) {
                    try {
                        Value mv = v.getMember(key);
                        // Skip methods/functions to avoid polluting data objects
                        if (mv != null && mv.canExecute()) continue;
                        map.put(key, convertValue(mv));
                    } catch (Exception ex) {
                        if (v.hasMember(key)) map.put(key, v.getMember(key).toString());
                    }
                }
                return map;
            }

            try {
                Object raw = v.as(Object.class);
                return (raw instanceof Value) ? v.toString() : raw;
            } catch (Exception ex) {
                return v.toString();
            }
        } catch (Exception ex) {
            return v.toString();
        }
    }

    private String toLanguageLiteral(Object value, String lang, String fallbackIdentifier) {
        boolean isCFamily = "c".equals(lang) || "cpp".equals(lang);
        if (value == null || lang == null) {
            if (isCFamily) return "0";
            if ("python".equals(lang)) return "None";
            if ("ruby".equals(lang)) return "nil";
            return "null";
        }

        if (value instanceof String) {
            String escaped = ((String) value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
            return "\"" + escaped + "\"";
        }

        if (value instanceof Boolean) {
            boolean b = (Boolean) value;
            if ("python".equals(lang)) return b ? "True" : "False";
            if (isCFamily) return b ? "1" : "0";
            return b ? "true" : "false";
        }

        if (value instanceof Number) {
            String val = String.valueOf(value);
            // Clean up trailing .0 for integers coming from JS/LLVM to satisfy strict Python assertions
            if (val.endsWith(".0")) {
                return val.substring(0, val.length() - 2);
            }
            return val;
        }

        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            String open = isCFamily ? "{" : "[";
            String close = isCFamily ? "}" : "]";
            StringBuilder sb = new StringBuilder(open);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(toLanguageLiteral(list.get(i), lang, fallbackIdentifier));
            }
            sb.append(close);
            return sb.toString();
        }

        if (value instanceof Map<?, ?>) {
            if (isCFamily) {
                // C/C++ has no native Map literal. Participation is best achieved 
                // by passing the data as a JSON string literal.
                try {
                    String json = JSON.writeValueAsString(value);
                    return toLanguageLiteral(json, lang, fallbackIdentifier);
                } catch (Exception e) {
                    return fallbackIdentifier;
                }
            }
            
            Map<?, ?> map = (Map<?, ?>) value;
            String kvSeparator = "ruby".equals(lang) ? " => " : ": ";
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i++ > 0) sb.append(", ");
                String key = String.valueOf(entry.getKey()).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("\"").append(key).append("\"").append(kvSeparator);
                sb.append(toLanguageLiteral(entry.getValue(), lang, fallbackIdentifier));
            }
            sb.append("}");
            return sb.toString();
        }

        // Fallback keeps previous behavior if we cannot serialize the value.
        return fallbackIdentifier;
    }

    private Object tryLoadFromJsonFile(String name, Map<String, Object> sharedMemory) {
        boolean isExplicitFile = name.endsWith(".json") || name.contains("/") || name.contains("\\");
        try {
            String projectPath = (String) sharedMemory.get("_projectPath");
            Path filePath = null;

            if (isExplicitFile) {
                filePath = projectPath != null ? Path.of(projectPath, name) : Path.of(name);
            } else {
                if (projectPath != null) {
                    Path jsonPath = Path.of(projectPath, name + ".json");
                    if (Files.exists(jsonPath)) filePath = jsonPath;
                } else {
                    filePath = Path.of(name + ".json");
                }
            }

            if (filePath != null && Files.exists(filePath)) {
                String jsonContent = Files.readString(filePath, StandardCharsets.UTF_8);
                Object imported = JSON.readValue(jsonContent, Object.class);
                sharedMemory.put(name, imported);
                System.out.println("ðŸ“¥ Imported object '" + name + "' from file");
                return imported;
            }
        } catch (Exception e) {
            System.out.println("âš ï¸ Failed to load import from file: " + e.getMessage());
        }
        return null;
    }

    private Object parseExportLiteral(String expr) {
        Object primitive = parsePrimitiveLiteral(expr);
        if (primitive != null) return primitive;
        return parseJsonLikeLiteral(expr);
    }

    private String deriveExitCodeText(Value result) {
        if (result == null) return null;
        try {
            if (result.fitsInInt()) return String.valueOf(result.asInt());
            if (result.fitsInLong()) return String.valueOf(result.asLong());
        } catch (Exception ignored) {
            // ignore conversion errors and fall through
        }
        return null;
    }

    private Object parsePrimitiveLiteral(String expr) {
        if (expr == null) return null;
        String t = expr.trim();
        if (t.isEmpty()) return null;

        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            String inner = t.substring(1, t.length() - 1);
            return inner
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");
        }

        if ("true".equalsIgnoreCase(t)) return true;
        if ("false".equalsIgnoreCase(t)) return false;

        if (t.matches("-?\\d+")) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException nfe) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException ignored) {
                    return t;
                }
            }
        }

        if (t.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException ignored) {
                return t;
            }
        }

        return null;
    }

    private Object parseJsonLikeLiteral(String expr) {
        if (expr == null) return null;
        String t = expr.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            return JSON.readValue(t, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> extractImportNames(String code) {
        Set<String> names = new LinkedHashSet<>();
        if (code == null || code.isBlank()) return names;
        Pattern importPattern = Pattern.compile("@import\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
        Matcher matcher = importPattern.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private List<Integer> findImportLines(String code, String importName) {
        List<Integer> lines = new ArrayList<>();
        if (code == null || code.isBlank() || importName == null || importName.isBlank()) return lines;

        String[] split = code.split("\\r?\\n", -1);
        Pattern p = Pattern.compile("@import\\s*\\(\\s*[\"']" + Pattern.quote(importName) + "[\"']\\s*\\)");
        for (int i = 0; i < split.length; i++) {
            if (p.matcher(split[i]).find()) {
                lines.add(i + 1);
            }
        }
        return lines;
    }

    private String joinLineNumbers(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private String enrichPolyglotErrorWithLine(PolyglotException pe, String err) {
        String safeErr = err == null ? "Execution failed" : err;
        try {
            if (pe != null && pe.getSourceLocation() != null) {
                int line = pe.getSourceLocation().getStartLine();
                if (line > 0 && !safeErr.contains("line " + line)) {
                    return "Line " + line + ": " + safeErr;
                }
            }
        } catch (Exception ignored) {
            // best effort only
        }
        return enrichTextErrorWithLine(safeErr);
    }

    private String enrichTextErrorWithLine(String err) {
        if (err == null || err.isBlank()) return err;

        Matcher bracketLine = Pattern.compile("\\[(\\d+)\\]:\\s*\\[(\\d+)\\]").matcher(err);
        if (bracketLine.find()) {
            return "Line " + bracketLine.group(1) + ", Col " + bracketLine.group(2) + ": " + err;
        }

        Matcher msvcLine = Pattern.compile("\\((\\d+),(\\d+)\\)").matcher(err);
        if (msvcLine.find()) {
            return "Line " + msvcLine.group(1) + ", Col " + msvcLine.group(2) + ": " + err;
        }

        Matcher pythonLine = Pattern.compile("line\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(err);
        if (pythonLine.find()) {
            return "Line " + pythonLine.group(1) + ": " + err;
        }

        Matcher javaLine = Pattern.compile("([^:]+\\.java):(\\d+):", Pattern.CASE_INSENSITIVE).matcher(err);
        if (javaLine.find()) {
            return "Line " + javaLine.group(2) + ": " + err;
        }

        return err;
    }

    private Path compileToLlvmBitcode(String sourceCode, boolean cpp) throws Exception {
        Path tempDir = Files.createTempDirectory("thevine-llvm-");
        try {
            Path source = tempDir.resolve(cpp ? "snippet.cpp" : "snippet.c");
            Path bitcode = tempDir.resolve("snippet.bc");
            return performCompilation(source, bitcode, sourceCode, cpp);
        } finally {
            // Schedule directory for deletion on JVM exit at minimum, 
            // though a more immediate cleanup strategy is recommended.
            File dir = tempDir.toFile();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.deleteOnExit();
                }
            }
            dir.deleteOnExit();
        }
    }

    private Path performCompilation(Path source, Path bitcode, String sourceCode, boolean cpp) throws Exception {
        Files.writeString(source, sourceCode, StandardCharsets.UTF_8);

        List<String> compilers = new ArrayList<>();
        if (cpp) {
            // Absolute paths first (works even if backend process PATH is stale)
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang++.exe");
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang-cl.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang++.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang-cl.exe");
            // PATH-based fallbacks
            compilers.addAll(Arrays.asList("clang++", "clang++-18", "clang++-17", "clang-cl"));
        } else {
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang.exe");
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang-cl.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang-cl.exe");
            compilers.addAll(Arrays.asList("clang", "clang-18", "clang-17", "clang-cl"));
        }

        List<String> systemIncludeDirs = discoverWindowsIncludeDirs();

        StringBuilder attempts = new StringBuilder();
        for (String compiler : compilers) {
            List<String> cmd = new ArrayList<>();
            if (isClangClCompiler(compiler)) {
                // Visual Studio clang-cl syntax
                cmd.add(compiler);
                cmd.add("/c");
                cmd.add(source.toString());
                cmd.add("/clang:-emit-llvm");
                cmd.add("/Fo" + bitcode.toString());
                for (String inc : systemIncludeDirs) {
                    cmd.add("/imsvc" + inc);
                }
            } else {
                // clang/clang++ syntax
                cmd.add(compiler);
                cmd.add("-O0");
                cmd.add("-emit-llvm");
                cmd.add("-c");
                // Only force MSVC target when we actually discovered Windows SDK/MSVC headers.
                // Otherwise, let clang use its default toolchain so standard headers like stdio.h
                // can still resolve on non-MSVC setups.
                if (isClangLikeCompiler(compiler) && !systemIncludeDirs.isEmpty()) {
                    cmd.add("--target=x86_64-pc-windows-msvc");
                    for (String inc : systemIncludeDirs) {
                        cmd.add("-isystem");
                        cmd.add(inc);
                    }
                }
                cmd.add(source.toString());
                cmd.add("-o");
                cmd.add(bitcode.toString());
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            try {
                Process p = pb.start();
                String output;
                try (InputStream is = p.getInputStream()) {
                    output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                int code = p.waitFor();
                if (code == 0 && Files.exists(bitcode)) {
                    return bitcode;
                }
                attempts.append("[").append(compiler).append("] exit=").append(code);
                if (output != null && !output.isBlank()) {
                    attempts.append(" -> ").append(output.trim());
                }
                attempts.append("\n");
            } catch (IOException ioe) {
                attempts.append("[").append(compiler).append("] not found\n");
            }
        }

        throw new RuntimeException(
            "Failed to compile C/C++ to LLVM bitcode. Ensure clang is installed and Windows SDK/MSVC headers are available.\n" + attempts
        );
    }

    private void addIfExists(List<String> compilers, String absolutePath) {
        if (Files.exists(Path.of(absolutePath))) {
            compilers.add(absolutePath);
        }
    }

    private List<String> discoverWindowsIncludeDirs() {
        List<String> dirs = new ArrayList<>();
        try {
            Path msvcRoot = Path.of("C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC");
            Path msvcVer = latestVersionDir(msvcRoot);
            if (msvcVer != null) {
                Path inc = msvcVer.resolve("include");
                addIncludeDir(dirs, inc);
            }

            Path kitsRoot = Path.of("C:\\Program Files (x86)\\Windows Kits\\10\\Include");
            Path kitsVer = latestVersionDir(kitsRoot);
            if (kitsVer != null) {
                for (String sub : Arrays.asList("ucrt", "um", "shared", "winrt", "cppwinrt")) {
                    Path p = kitsVer.resolve(sub);
                    addIncludeDir(dirs, p);
                }
            }

            // Also honor user/system environment include directories when available.
            addIncludeDirsFromEnv(dirs, "INCLUDE", ";");
            addIncludeDirsFromEnv(dirs, "CPATH", ";");
            addIncludeDirsFromEnv(dirs, "C_INCLUDE_PATH", ";");
            addIncludeDirsFromEnv(dirs, "CPLUS_INCLUDE_PATH", ";");
        } catch (Exception ignored) {
            // best effort
        }
        return dirs;
    }

    private boolean isClangClCompiler(String compiler) {
        if (compiler == null || compiler.isBlank()) return false;
        String normalized = compiler.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.endsWith("/clang-cl") || normalized.endsWith("/clang-cl.exe")
            || "clang-cl".equals(normalized)
            || "clang-cl.exe".equals(normalized);
    }

    private boolean isClangLikeCompiler(String compiler) {
        if (compiler == null || compiler.isBlank()) return false;
        String normalized = compiler.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = normalized;
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            name = normalized.substring(slash + 1);
        }
        return name.startsWith("clang") || name.startsWith("clang++");
    }

    private void addIncludeDir(List<String> dirs, Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        String val = dir.toString();
        if (!dirs.contains(val)) {
            dirs.add(val);
        }
    }

    private void addIncludeDirsFromEnv(List<String> dirs, String envName, String separator) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return;

        String[] parts = raw.split(Pattern.quote(separator));
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                addIncludeDir(dirs, Path.of(trimmed));
            } catch (Exception ignored) {
                // ignore malformed path entries
            }
        }
    }

    private boolean shouldUseNativeFallbackForCFamily(PolyglotException pe) {
        if (pe == null) return false;
        String err = pe.getMessage();
        if (err == null) return false;
        return err.contains("missing LLVM builtin: llvm.va_start")
            || err.contains("Unsupported opCode in constant block");
    }

    private NativeRunResult compileAndRunNative(String sourceCode, boolean cpp) throws Exception {
        Path tempDir = Files.createTempDirectory("thevine-native-");
        Path source = tempDir.resolve(cpp ? "snippet.cpp" : "snippet.c");
        Path executable = tempDir.resolve("snippet.exe");
        Path objectFile = tempDir.resolve("snippet.obj");
        Files.writeString(source, sourceCode, StandardCharsets.UTF_8);

        boolean hasMain = Pattern.compile("\\bint\\s+main\\s*\\(", Pattern.MULTILINE).matcher(sourceCode).find();

        List<String> compilers = new ArrayList<>();
        if (cpp) {
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang++.exe");
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang-cl.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang++.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang-cl.exe");
            compilers.addAll(Arrays.asList("clang++", "clang++-18", "clang++-17", "clang-cl", "g++", "g++-14", "g++-13"));
        } else {
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang.exe");
            addIfExists(compilers, "C:\\Program Files\\LLVM\\bin\\clang-cl.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang.exe");
            addIfExists(compilers, "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\Llvm\\x64\\bin\\clang-cl.exe");
            compilers.addAll(Arrays.asList("clang", "clang-18", "clang-17", "clang-cl", "gcc", "gcc-14", "gcc-13"));
        }

        List<String> systemIncludeDirs = discoverWindowsIncludeDirs();
        StringBuilder attempts = new StringBuilder();

        for (String compiler : compilers) {
            List<String> cmd = new ArrayList<>();
            if (isClangClCompiler(compiler)) {
                cmd.add(compiler);
                cmd.add(source.toString());
                if (hasMain) {
                    cmd.add("/Fe:" + executable.toString());
                } else {
                    cmd.add("/c");
                    cmd.add("/Fo" + objectFile.toString());
                }
                if (cpp) {
                    cmd.add("/EHsc");
                }
                for (String inc : systemIncludeDirs) {
                    cmd.add("/imsvc" + inc);
                }
            } else {
                cmd.add(compiler);
                cmd.add(source.toString());
                cmd.add("-O0");
                if (isClangLikeCompiler(compiler) && !systemIncludeDirs.isEmpty()) {
                    cmd.add("--target=x86_64-pc-windows-msvc");
                    for (String inc : systemIncludeDirs) {
                        cmd.add("-isystem");
                        cmd.add(inc);
                    }
                }
                if (hasMain) {
                    cmd.add("-o");
                    cmd.add(executable.toString());
                } else {
                    cmd.add("-c");
                    cmd.add("-o");
                    cmd.add(objectFile.toString());
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            try {
                Process p = pb.start();
                String compileStdout;
                String compileStderr;
                try (InputStream os = p.getInputStream(); InputStream es = p.getErrorStream()) {
                    compileStdout = new String(os.readAllBytes(), StandardCharsets.UTF_8);
                    compileStderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                int compileCode = p.waitFor();
                if (!hasMain && compileCode == 0 && Files.exists(objectFile)) {
                    return new NativeRunResult("", "", null);
                }

                if (hasMain && compileCode == 0 && Files.exists(executable)) {
                    ProcessBuilder runPb = new ProcessBuilder(executable.toString());
                    Process run = runPb.start();
                    String runStdout;
                    String runStderr;
                    try (InputStream ros = run.getInputStream(); InputStream res = run.getErrorStream()) {
                        runStdout = new String(ros.readAllBytes(), StandardCharsets.UTF_8);
                        runStderr = new String(res.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    int runCode = run.waitFor();
                    return new NativeRunResult(runStdout, runStderr, runCode);
                }
                attempts.append("[").append(compiler).append("] exit=").append(compileCode);
                String compileOut = ((compileStdout == null ? "" : compileStdout) + (compileStderr == null ? "" : compileStderr)).trim();
                if (!compileOut.isBlank()) {
                    attempts.append(" -> ").append(compileOut);
                }
                attempts.append("\n");
            } catch (IOException ioe) {
                attempts.append("[").append(compiler).append("] not found\n");
            }
        }

        throw new RuntimeException(
            "Failed to compile and run native C/C++ fallback. Ensure a native compiler (clang/gcc) is installed.\n" + attempts
        );
    }

    /**
     * Compile Java source and run it via GraalVM Espresso (Java on Truffle).
     * Espresso does not support ctx.eval() of raw Java source code.
     * Instead, we compile the source to bytecode with javac, then use
     * Espresso's polyglot bindings to load and invoke the compiled class.
     */
    private NativeRunResult compileAndRunViaEspresso(Context ctx, String sourceCode, List<Export> exports, String jacksonClasspath) throws Exception {
        Path tempDir = Files.createTempDirectory("thevine-espresso-");

        String className = "Main";
        Matcher classMatcher = Pattern.compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(sourceCode);
        if (classMatcher.find()) {
            className = classMatcher.group(1);
        }

        Path source = tempDir.resolve(className + ".java");

        // --- Inject export statements into sourceCode (same as native path) ---
        StringBuilder modifiedSource = new StringBuilder(sourceCode);

        String codeStr = modifiedSource.toString();
        int importInsertPos = 0;
        Matcher pkgMatcher = Pattern.compile("package\\s+[a-zA-Z0-9.]+;").matcher(codeStr);
        if (pkgMatcher.find()) importInsertPos = pkgMatcher.end();

        modifiedSource.insert(importInsertPos, "\nimport com.fasterxml.jackson.databind.ObjectMapper;\nimport com.fasterxml.jackson.core.type.TypeReference;\n");

        int insertionPoint = modifiedSource.lastIndexOf("}");
        String currentCode = modifiedSource.toString();
        int mainMethodStart = currentCode.indexOf("public static void main");
        if (mainMethodStart != -1) {
            int classEnd = currentCode.lastIndexOf("}");
            int mainMethodEnd = currentCode.substring(0, classEnd).lastIndexOf("}");
            if (mainMethodEnd != -1) insertionPoint = mainMethodEnd;
        }
        if (insertionPoint == -1) insertionPoint = modifiedSource.length();

        for (Export exp : exports) {
            if (!"java".equals(exp.getLanguage())) continue;
            String val = exp.getValue();
            String injection;
            // If the expression already produces a JSON string (e.g. mapper.writeValueAsString(...)),
            // don't wrap it again with writeValueAsString - just concatenate directly.
            if (val.contains("writeValueAsString") || val.contains("toJson") || val.contains("toString")) {
                injection = String.format(
                    "\n        try { System.out.println(\"THEVINE_EXPORT_%s:\" + %s); } catch(Exception e) {}\n",
                    exp.getName(), val
                );
            } else {
                injection = String.format(
                    "\n        try { System.out.println(\"THEVINE_EXPORT_%s:\" + new ObjectMapper().writeValueAsString(%s)); } catch(Exception e) {}\n",
                    exp.getName(), val
                );
            }
            modifiedSource.insert(insertionPoint, injection);
        }
        Files.writeString(source, modifiedSource.toString(), StandardCharsets.UTF_8);

        // --- Compile with javac ---
        List<String> javacCandidates = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addIfExists(javacCandidates, Path.of(javaHome, "bin", "javac.exe").toString());
        }
        javacCandidates.add("javac");

        StringBuilder compileAttempts = new StringBuilder();
        boolean compiled = false;
        for (String javac : javacCandidates) {
            List<String> cmd = Arrays.asList(javac, "-cp", jacksonClasspath, source.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            try {
                Process p = pb.start();
                String stderr;
                try (InputStream es = p.getErrorStream()) {
                    stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                int code = p.waitFor();
                if (code == 0) {
                    compiled = true;
                    break;
                }
                compileAttempts.append("[").append(javac).append("] exit=").append(code);
                if (stderr != null && !stderr.isBlank()) compileAttempts.append(" -> ").append(stderr.trim());
                compileAttempts.append("\n");
            } catch (IOException ioe) {
                compileAttempts.append("[").append(javac).append("] not found\n");
            }
        }

        if (!compiled) {
            throw new RuntimeException("Failed to compile Java block for Espresso.\n" + compileAttempts);
        }

        boolean hasMain = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(").matcher(sourceCode).find();
        if (!hasMain) {
            return new NativeRunResult("", "", null);
        }

        // --- Run via Espresso: load the compiled class and invoke main() ---
        try {
            // Use Espresso bindings to load the class by name.
            // First, we need to add the tempDir to Espresso's classpath.
            // Since we already set java.Classpath in configureJavaContext, we add tempDir.
            // Espresso resolves classes from the classpath set at context creation time,
            // so we use a fresh context approach or load via Class.forName through bindings.
            Value javaBindings = ctx.getBindings("java");

            // Get java.lang.Class through Espresso
            Value classType = javaBindings.getMember("java.lang.Class");

            // We need to add our temp dir to the classpath. Since Espresso's classpath is
            // set at context creation, and we can't modify it after, we fall back to
            // running via the native java command but capture output through the polyglot streams.
            // This still benefits from the Espresso engine being available for interop.
            String runtimeCp = tempDir.toString() + File.pathSeparator + jacksonClasspath;

            List<String> javaCandidates = new ArrayList<>();
            if (javaHome != null && !javaHome.isBlank()) {
                addIfExists(javaCandidates, Path.of(javaHome, "bin", "java.exe").toString());
            }
            javaCandidates.add("java");

            StringBuilder runAttempts = new StringBuilder();
            for (String javaCmd : javaCandidates) {
                List<String> cmd = Arrays.asList(javaCmd, "-cp", runtimeCp, className);
                ProcessBuilder runPb = new ProcessBuilder(cmd);
                try {
                    Process p = runPb.start();
                    String stdout;
                    String stderr;
                    try (InputStream os = p.getInputStream(); InputStream es = p.getErrorStream()) {
                        stdout = new String(os.readAllBytes(), StandardCharsets.UTF_8);
                        stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    int exitCode = p.waitFor();
                    if (exitCode == 0) {
                        return new NativeRunResult(stdout, stderr, exitCode);
                    }
                    runAttempts.append("[").append(javaCmd).append("] exit=").append(exitCode);
                    String runOut = ((stdout == null ? "" : stdout) + (stderr == null ? "" : stderr)).trim();
                    if (!runOut.isBlank()) runAttempts.append(" -> ").append(runOut);
                    runAttempts.append("\n");
                } catch (IOException ioe) {
                    runAttempts.append("[").append(javaCmd).append("] not found\n");
                }
            }
            throw new RuntimeException("Failed to run Java block via Espresso.\n" + runAttempts);
        } catch (PolyglotException pe) {
            // If Espresso interop fails, fall back to native execution
            System.err.println("☕ Espresso interop failed, falling back to native Java: " + pe.getMessage());
            return compileAndRunNativeJava(sourceCode, exports, jacksonClasspath);
        }
    }

    private NativeRunResult compileAndRunNativeJava(String sourceCode, List<Export> exports, String jacksonClasspath) throws Exception {
        Path tempDir = Files.createTempDirectory("thevine-java-");

        String className = "Main";
        Matcher classMatcher = Pattern.compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(sourceCode);
        if (classMatcher.find()) {
            className = classMatcher.group(1);
        }

        Path source = tempDir.resolve(className + ".java");

        // --- Start: Inject export statements into sourceCode ---
        StringBuilder modifiedSource = new StringBuilder(sourceCode);
        
        // Handle imports: find package declaration to insert after, or insert at top
        String codeStr = modifiedSource.toString();
        int importInsertPos = 0;
        Matcher pkgMatcher = Pattern.compile("package\\s+[a-zA-Z0-9.]+;").matcher(codeStr);
        if (pkgMatcher.find()) importInsertPos = pkgMatcher.end();

        modifiedSource.insert(importInsertPos, "\nimport com.fasterxml.jackson.databind.ObjectMapper;\nimport com.fasterxml.jackson.core.type.TypeReference;\n");

        // Find a suitable insertion point for export statements.
        // This is a heuristic: try to find the end of the main method or the class.
        int insertionPoint = modifiedSource.lastIndexOf("}"); // Default to end of class
        String currentCode = modifiedSource.toString();
        int mainMethodStart = currentCode.indexOf("public static void main");
        if (mainMethodStart != -1) {
            // More robust heuristic for snippets: find the last brace of the file (class end)
            // and then find the brace immediately before it (method end).
            int classEnd = currentCode.lastIndexOf("}");
            int mainMethodEnd = currentCode.substring(0, classEnd).lastIndexOf("}");
            if (mainMethodEnd != -1) insertionPoint = mainMethodEnd;
        }
        if (insertionPoint == -1) insertionPoint = modifiedSource.length(); // Fallback to end of file

        for (Export exp : exports) {
            if (!"java".equals(exp.getLanguage())) continue;
            String val = exp.getValue();
            String injection;
            if (val.contains("writeValueAsString") || val.contains("toJson") || val.contains("toString")) {
                injection = String.format(
                    "\n        try { System.out.println(\"THEVINE_EXPORT_%s:\" + %s); } catch(Exception e) {}\n",
                    exp.getName(), val
                );
            } else {
                injection = String.format(
                    "\n        try { System.out.println(\"THEVINE_EXPORT_%s:\" + new ObjectMapper().writeValueAsString(%s)); } catch(Exception e) {}\n",
                    exp.getName(), val
                );
            }
            modifiedSource.insert(insertionPoint, injection);
        }
        // --- End: Inject export statements into sourceCode ---
        Files.writeString(source, modifiedSource.toString(), StandardCharsets.UTF_8);

        List<String> javacCandidates = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addIfExists(javacCandidates, Path.of(javaHome, "bin", "javac.exe").toString());
        }
        javacCandidates.add("javac");

        StringBuilder compileAttempts = new StringBuilder();
        String compileErr = "";
        boolean compiled = false;
        for (String javac : javacCandidates) {
            // Include jacksonClasspath so injected ObjectMapper code compiles
            List<String> cmd = Arrays.asList(javac, "-cp", jacksonClasspath, source.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            try {
                Process p = pb.start();
                String stderr;
                try (InputStream es = p.getErrorStream()) {
                    stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                int code = p.waitFor();
                if (code == 0) {
                    compiled = true;
                    break;
                }
                compileErr = stderr == null ? "" : stderr;
                compileAttempts.append("[").append(javac).append("] exit=").append(code);
                if (!compileErr.isBlank()) compileAttempts.append(" -> ").append(compileErr.trim());
                compileAttempts.append("\n");
            } catch (IOException ioe) {
                compileAttempts.append("[").append(javac).append("] not found\n");
            }
        }

        if (!compiled) {
            throw new RuntimeException("Failed to compile Java block.\n" + compileAttempts);
        }

        boolean hasMain = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(").matcher(sourceCode).find();
        if (!hasMain) {
            return new NativeRunResult("", "", null);
        }

        List<String> javaCandidates = new ArrayList<>();
        if (javaHome != null && !javaHome.isBlank()) {
            addIfExists(javaCandidates, Path.of(javaHome, "bin", "java.exe").toString());
        }
        javaCandidates.add("java");

        StringBuilder runAttempts = new StringBuilder();
        for (String javaCmd : javaCandidates) {
            // Include jacksonClasspath and tempDir in the runtime classpath
            String runtimeCp = tempDir.toString() + File.pathSeparator + jacksonClasspath;
            List<String> cmd = Arrays.asList(javaCmd, "-cp", runtimeCp, className);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            try {
                Process p = pb.start();
                String stdout;
                String stderr;
                try (InputStream os = p.getInputStream(); InputStream es = p.getErrorStream()) {
                    stdout = new String(os.readAllBytes(), StandardCharsets.UTF_8);
                    stderr = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                int code = p.waitFor();
                if (code == 0) {
                    return new NativeRunResult(stdout, stderr, code);
                }
                runAttempts.append("[").append(javaCmd).append("] exit=").append(code);
                String runOut = ((stdout == null ? "" : stdout) + (stderr == null ? "" : stderr)).trim();
                if (!runOut.isBlank()) runAttempts.append(" -> ").append(runOut);
                runAttempts.append("\n");
            } catch (IOException ioe) {
                runAttempts.append("[").append(javaCmd).append("] not found\n");
            }
        }

        throw new RuntimeException("Failed to run Java block.\n" + runAttempts);
    }

    private static final class NativeRunResult {
        private final String stdout;
        private final String stderr;
        private final Integer exitCode;

        private NativeRunResult(String stdout, String stderr, Integer exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    private Path latestVersionDir(Path root) {
        if (root == null || !Files.isDirectory(root)) return null;
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                .max(Comparator.comparing(p -> p.getFileName().toString()))
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inject printf/cout statements into C/C++ source code to output export markers
     * when running via native compilation (not Sulung). This allows cross-block data
     * flow from native C/C++ blocks by parsing THEVINE_EXPORT markers from stdout.
     *
     * For C: injects printf("THEVINE_EXPORT_name:\"%s\"\n", expr) before return.
     * For C++: injects std::cout << "THEVINE_EXPORT_name:\"" << expr << "\"" << std::endl;
     */
    private String injectCFamilyNativeExports(String code, List<Export> exports, String lang, boolean cpp) {
        if (exports == null || exports.isEmpty()) return code;

        // Collect exports for this language
        List<Export> relevantExports = new ArrayList<>();
        for (Export exp : exports) {
            if (exp.getLanguage().equals(lang)) {
                relevantExports.add(exp);
            }
        }
        if (relevantExports.isEmpty()) return code;

        // Find insertion point: just before the last "return" in main()
        // or before the closing brace of main() if no return exists
        boolean hasMain = Pattern.compile("\\bint\\s+main\\s*\\(", Pattern.MULTILINE).matcher(code).find();
        if (!hasMain) return code;

        // Find the opening brace of main
        Matcher mainMatcher = Pattern.compile("\\bint\\s+main\\s*\\([^)]*\\)\\s*\\{").matcher(code);
        if (!mainMatcher.find()) return code;
        int mainOpenBrace = mainMatcher.end() - 1;

        // Find matching close brace, skipping characters inside string/char literals
        int depth = 0, mainCloseBrace = -1;
        boolean inString = false, inChar = false;
        for (int i = mainOpenBrace; i < code.length(); i++) {
            char c = code.charAt(i);
            // Skip escaped characters inside literals
            if (c == '\\' && (inString || inChar) && i + 1 < code.length()) {
                i++; // skip next char (escaped)
                continue;
            }
            if (c == '"' && !inChar) { inString = !inString; continue; }
            if (c == '\'' && !inString) { inChar = !inChar; continue; }
            if (inString || inChar) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { mainCloseBrace = i; break; } }
        }
        if (mainCloseBrace == -1) return code;

        String mainBody = code.substring(mainOpenBrace + 1, mainCloseBrace);

        // Find last return statement in main body
        Pattern returnPat = Pattern.compile("\\breturn\\b[^;]*;");
        Matcher returnMat = returnPat.matcher(mainBody);
        int lastReturnPos = -1;
        while (returnMat.find()) {
            lastReturnPos = returnMat.start();
        }

        int insertPos;
        if (lastReturnPos >= 0) {
            insertPos = mainOpenBrace + 1 + lastReturnPos;
        } else {
            insertPos = mainCloseBrace;
        }

        // Build export printf/cout statements
        StringBuilder exportStmts = new StringBuilder("\n");
        for (Export exp : relevantExports) {
            String varExpr = exp.getValue();
            String exportName = exp.getName();

            if (cpp) {
                // For C++, use std::cout. Output raw value without extra quotes.
                // The parsing side will handle both JSON objects and plain strings.
                exportStmts.append("    std::cout << \"THEVINE_EXPORT_")
                           .append(exportName)
                           .append(":\" << ")
                           .append(varExpr)
                           .append(" << std::endl;\n");
            } else {
                // For C, use printf with %s format for char*/string exports.
                // Output raw value without extra quotes.
                exportStmts.append("    printf(\"THEVINE_EXPORT_")
                           .append(exportName)
                           .append(":%s\\n\", ")
                           .append(varExpr)
                           .append(");\n");
            }
        }

        return code.substring(0, insertPos) + exportStmts.toString() + code.substring(insertPos);
    }

    /**
     * Safe replacement that avoids regex replacement semantics for the replacement string.
     * Uses Matcher.find() + StringBuilder to insert the literal replacement text,
     * preventing corruption of backslashes and dollar signs in C/C++ string literals.
     */
    private String safeReplaceAll(String input, Pattern pattern, String replacement) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            sb.append(input, lastEnd, m.start());
            sb.append(replacement);
            lastEnd = m.end();
        }
        sb.append(input, lastEnd, input.length());
        return sb.toString();
    }

    /**
     * Pre-compilation transform for C/C++ blocks.
     *
     * Sulong (the LLVM JIT) can only read module-level LLVM globals from Java.
     * Local variables declared inside main() or any function body are invisible
     * as module-level symbols. When a C/C++ @export targets such a local variable,
      * the export silently fails and all downstream blocks that depend on it also fail.
      *
      * This method detects exported identifiers that are only declared as block-scope
      * locals (not already visible file-scope globals) and injects two code changes:
      *  1. A file-scope C/C++ forward declaration (e.g. {@code int __thevine_exp_result;}
      *     or {@code double __thevine_exp_x;})
      *  2. An assignment statement inside the function body that copies the local value
      *     to the forward-declared global before the function returns.
      *
      * After this transform the forward-declared global is a proper module-level LLVM
      * symbol that Sulong exposes as a context member.
      */
    private String injectCFamilyExportGlobals(String code, java.util.Set<String> exportedNames, boolean cpp) {
         int bodyBracePos = code.indexOf("{");
         if (bodyBracePos == -1) return code;

         int d = 0, bodyClosePos = -1;
         for (int i = bodyBracePos; i < code.length(); i++) {
                 char c = code.charAt(i);
                 if (c == '{') { d++; }
                 else if (c == '}') { d--; if (d == 0) { bodyClosePos = i; break; } }
         }
         if (bodyClosePos == -1) return code;

         // Refined pattern to avoid matching preprocessor directives like #include
         java.util.regex.Pattern declPattern =
             java.util.regex.Pattern.compile(
                 "^\\s*([\\w\\s\\*&]+(?:\\[[^\\]]*\\])?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;|,|\\s)", java.util.regex.Pattern.MULTILINE);
         java.util.Map<String, String> foundTypes = new LinkedHashMap<>();

         for (String name : exportedNames) {
             java.util.regex.Matcher m = declPattern.matcher(code);
             while (m.find()) {
                 if (name.equals(m.group(2))) {
                     String decl = m.group(0).trim();
                     if (decl.endsWith("(")) continue;
                     if (m.start() < bodyBracePos) continue;
                     foundTypes.put(name, m.group(1).trim());
                     break;
                 }
             }
         }
         if (foundTypes.isEmpty()) return code;

         // --- FIX: Forward declarations go at FILE SCOPE, before the function def line --
         // Find the start of the line containing the opening brace (the function def line)
         int funcLineStart = bodyBracePos;
         while (funcLineStart > 0 && code.charAt(funcLineStart - 1) != '\n') {
             funcLineStart--;
         }

         // --- FIX: Assignments go BEFORE the last return statement, not after it --
         // Extract the function body (between { and })
         String bodyCode = code.substring(bodyBracePos + 1, bodyClosePos);
         java.util.regex.Pattern returnPattern = java.util.regex.Pattern.compile("\\breturn\\b[^;]*;");
         java.util.regex.Matcher returnMatcher = returnPattern.matcher(bodyCode);
         int lastReturnBodyPos = -1;
         while (returnMatcher.find()) {
             lastReturnBodyPos = returnMatcher.start();
         }

         int insertPosInCode;  // absolute position in code for assignment insertion
         String bodyIndent;

         if (lastReturnBodyPos >= 0) {
             // Insert assignments just before the last return statement
             insertPosInCode = bodyBracePos + 1 + lastReturnBodyPos;
             // Extract indentation from the return line
             int lineStartInBody = lastReturnBodyPos;
             while (lineStartInBody > 0 && bodyCode.charAt(lineStartInBody - 1) != '\n') {
                 lineStartInBody--;
             }
             String returnLinePre = bodyCode.substring(lineStartInBody, lastReturnBodyPos);
             bodyIndent = returnLinePre.replaceAll("\\S.*", "");
         } else {
             // No explicit return: insert before the closing brace
             insertPosInCode = bodyClosePos;
             int wsStart = bodyClosePos;
             while (wsStart > bodyBracePos && (code.charAt(wsStart - 1) == ' ' || code.charAt(wsStart - 1) == '\t'
                         || code.charAt(wsStart - 1) == '\n' || code.charAt(wsStart - 1) == '\r')) {
                 wsStart--;
             }
             bodyIndent = code.substring(wsStart, bodyClosePos);
         }

         // Build forward declarations (at file scope) and assignment statements
         StringBuilder fwdBuf = new StringBuilder();
         StringBuilder asnBuf = new StringBuilder();
         if (cpp) fwdBuf.append("extern \"C\" {\n");
         for (java.util.Map.Entry<String, String> e : foundTypes.entrySet()) {
             fwdBuf.append(e.getValue()).append(" __thevine_exp_").append(e.getKey()).append(";\n");
             asnBuf.append(bodyIndent)
                   .append("__thevine_exp_").append(e.getKey())
                   .append(" = ((").append(e.getValue()).append(") ").append(e.getKey()).append(");\n");
         }
         if (cpp) fwdBuf.append("}\n");

         // Assemble:
         //   [everything before function-def line] + [fwd declarations at file scope]
         //   + [function-def line through insertion point] + [assignments]
         //   + [rest of code from insertion point]
         return code.substring(0, funcLineStart)
                 + fwdBuf.toString()
                 + code.substring(funcLineStart, insertPosInCode)
                 + asnBuf.toString()
                 + code.substring(insertPosInCode);
     }
    }
