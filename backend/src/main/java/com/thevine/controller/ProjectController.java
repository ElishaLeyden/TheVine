package com.thevine.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "http://localhost:3000")
public class ProjectController {

    private static final String PROJECT_FILE_NAME = ".thevine";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/open")
    public ResponseEntity<?> openProject(@RequestBody Map<String, String> request) {
        String projectPathRaw = request.get("projectPath");
        if (projectPathRaw == null || projectPathRaw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath is required"));
        }

        try {
            Path projectPath = normalizeProjectPath(projectPathRaw);
            if (!Files.exists(projectPath) || !Files.isDirectory(projectPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project directory does not exist"));
            }

            Map<String, Object> metadata = readProjectMetadata(projectPath);
            Map<String, Object> response = buildProjectResponse(projectPath, metadata);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> request) {
        String projectPathRaw = String.valueOf(request.getOrDefault("projectPath", ""));
        String name = String.valueOf(request.getOrDefault("name", "Untitled Project"));

        if (projectPathRaw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath is required"));
        }

        try {
            Path projectPath = Path.of(projectPathRaw).toAbsolutePath().normalize();
            Files.createDirectories(projectPath);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", name);
            metadata.put("openFiles", new ArrayList<>());
            metadata.put("currentFile", null);
            metadata.put("settings", new LinkedHashMap<>());

            writeProjectMetadata(projectPath, metadata);

            return ResponseEntity.ok(buildProjectResponse(projectPath, metadata));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveProject(@RequestBody Map<String, Object> request) {
        String projectPathRaw = String.valueOf(request.getOrDefault("projectPath", ""));
        Object metadataObj = request.get("metadata");

        if (projectPathRaw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath is required"));
        }

        if (!(metadataObj instanceof Map)) {
            return ResponseEntity.badRequest().body(Map.of("error", "metadata object is required"));
        }

        try {
            Path projectPath = normalizeProjectPath(projectPathRaw);
            if (!Files.isDirectory(projectPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Project directory does not exist"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) metadataObj;
            writeProjectMetadata(projectPath, metadata);

            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tree")
    public ResponseEntity<?> getTree(@RequestParam("projectPath") String projectPathRaw) {
        try {
            Path projectPath = normalizeProjectPath(projectPathRaw);
            if (!Files.isDirectory(projectPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid projectPath"));
            }

            return ResponseEntity.ok(Map.of("tree", buildTree(projectPath, projectPath)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/file")
    public ResponseEntity<?> readFile(
        @RequestParam("projectPath") String projectPathRaw,
        @RequestParam("filePath") String relativeFilePath
    ) {
        try {
            Path projectPath = normalizeProjectPath(projectPathRaw);
            Path filePath = resolveInsideProject(projectPath, relativeFilePath);

            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "File does not exist"));
            }

            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/file")
    public ResponseEntity<?> writeFile(@RequestBody Map<String, String> request) {
        String projectPathRaw = request.get("projectPath");
        String relativeFilePath = request.get("filePath");
        String content = request.getOrDefault("content", "");

        if (projectPathRaw == null || projectPathRaw.isBlank() || relativeFilePath == null || relativeFilePath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath and filePath are required"));
        }

        try {
            Path projectPath = normalizeProjectPath(projectPathRaw);
            // normalize to .tv extension when the provided file name has no extension
            String normalizedRel = enforceTvExtension(relativeFilePath);
            Path filePath = resolveInsideProject(projectPath, normalizedRel);
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "filePath", projectPath.relativize(filePath).toString().replace('\\', '/')
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private Path normalizeProjectPath(String pathRaw) {
        return Path.of(pathRaw).toAbsolutePath().normalize();
    }

    private Path resolveInsideProject(Path projectPath, String relativeFilePath) {
        Path resolved = projectPath.resolve(relativeFilePath).normalize();
        if (!resolved.startsWith(projectPath)) {
            throw new RuntimeException("filePath escapes project directory");
        }
        return resolved;
    }

    private Path projectFilePath(Path projectPath) {
        return projectPath.resolve(PROJECT_FILE_NAME);
    }

    private Map<String, Object> readProjectMetadata(Path projectPath) throws IOException {
        Path projectFile = projectFilePath(projectPath);
        if (!Files.exists(projectFile)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", projectPath.getFileName() == null ? "TheVine Project" : projectPath.getFileName().toString());
            metadata.put("openFiles", new ArrayList<>());
            metadata.put("currentFile", null);
            metadata.put("settings", new LinkedHashMap<>());
            return metadata;
        }

        String json = Files.readString(projectFile, StandardCharsets.UTF_8);
        Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() {});
        if (!metadata.containsKey("openFiles")) metadata.put("openFiles", new ArrayList<>());
        if (!metadata.containsKey("currentFile")) metadata.put("currentFile", null);
        if (!metadata.containsKey("settings")) metadata.put("settings", new LinkedHashMap<>());
        if (!metadata.containsKey("name")) {
            metadata.put("name", projectPath.getFileName() == null ? "TheVine Project" : projectPath.getFileName().toString());
        }
        return metadata;
    }

    private void writeProjectMetadata(Path projectPath, Map<String, Object> metadata) throws IOException {
        Path projectFile = projectFilePath(projectPath);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Files.writeString(projectFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Map<String, Object> buildProjectResponse(Path projectPath, Map<String, Object> metadata) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectPath", projectPath.toString());
        response.put("projectFile", projectFilePath(projectPath).toString());
        response.put("metadata", metadata);
        response.put("tree", buildTree(projectPath, projectPath));
        return response;
    }

    private String enforceTvExtension(String relativeFilePath) {
        try {
            String unix = relativeFilePath.replace('\\', '/');
            Path p = Path.of(unix);
            String name = p.getFileName() == null ? unix : p.getFileName().toString();
            // do not change if it already has an extension
            if (name.contains(".")) return relativeFilePath;
            return relativeFilePath + ".tv";
        } catch (Exception ignored) {
            return relativeFilePath;
        }
    }

    private List<Map<String, Object>> buildTree(Path root, Path current) throws IOException {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (".".equals(name) || "..".equals(name)) continue;
                // Hide project metadata file from the File Explorer
                if (PROJECT_FILE_NAME.equals(name)) continue;

                boolean isDir = Files.isDirectory(child);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                node.put("path", root.relativize(child).toString().replace('\\', '/'));
                node.put("type", isDir ? "directory" : "file");
                if (isDir) {
                    node.put("children", buildTree(root, child));
                }
                nodes.add(node);
            }
        }

        nodes.sort((a, b) -> {
            String ta = String.valueOf(a.get("type"));
            String tb = String.valueOf(b.get("type"));
            if (!ta.equals(tb)) {
                return "directory".equals(ta) ? -1 : 1;
            }
            return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
        });

        return nodes;
    }
}
