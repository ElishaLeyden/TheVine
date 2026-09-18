# TheVine IDE

**A single-file polyglot IDE for declarative cross-language data exchange via GraalVM**

TheVine enables developers to write Python, JavaScript, Java, C, and C++ code within a single composite `.tv` file and share variables across language blocks using `@export` and `@import` directives, without requiring developer-written GraalVM Polyglot API code in the source file.

---

## What TheVine Does

In a conventional polyglot workflow, sharing data between languages may require manual serialisation, intermediate files, or other inter-process communication mechanisms. TheVine supports cross-language data exchange through a shared in-memory representation within its GraalVM-based execution workflow, reducing the need for developer-written serialization between supported language blocks.

A minimal example:

```text
@python
scores = [92, 85, 78, 96, 88]
mean = sum(scores) / len(scores)
@export('mean_score', mean)

@javascript
@import('mean_score')
console.log('Average:', mean_score);
```

The `mean_score` value flows from Python to JavaScript through TheVine's execution and type-bridging pipeline. The example does not require developer-written JSON serialization or intermediate file I/O.

---

## Architecture

TheVine is a cross-platform desktop application built around a React/Electron frontend and a Java/Spring Boot backend.

| Layer            | Technology                          |
| ---------------- | ----------------------------------- |
| Frontend         | React 18 + Monaco Editor + Electron |
| Backend API      | Java 21 + Spring Boot               |
| Execution engine | GraalVM                             |
| Build tools      | Apache Maven + Vite                 |

The middleware layer comprises four principal components:

* **CodeParser** — parses composite `.tv` files into language-specific blocks and identifies directives.
* **Dependency Resolver** — determines the execution order of blocks based on export/import dependencies.
* **ExecutionEngine** — coordinates GraalVM-based execution and cross-language data transfer.
* **WebSocket REPL** — provides interactive execution and communication with the backend.

---

## Repository Structure

```text
TheVine/
├── backend/
│   ├── src/main/java/com/thevine/
│   │   ├── controller/
│   │   ├── engine/
│   │   ├── parser/
│   │   └── websocket/
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── App.jsx
│   │   ├── TheVineEditor.jsx
│   │   ├── Terminal.jsx
│   │   └── InteractiveConsole.jsx
│   ├── package.json
│   └── vite.config.js
└── .gitignore
```

---

## Supported Languages and Directives

### Language Block Tags

| Tag                   | Language   |
| --------------------- | ---------- |
| `@python` / `@py`     | Python     |
| `@javascript` / `@js` | JavaScript |
| `@java`               | Java       |
| `@c`                  | C          |
| `@cpp`                | C++        |

### Directives

```text
@export('variable_name', expression)
@import('variable_name')
```

`@export` makes a value available to dependent language blocks, while `@import` declares a dependency on an exported value.

### Example

```text
@python
temperature = 25
@export('temperature', temperature)

@javascript
@import('temperature')
console.log("Temperature:", temperature)
```

---

## Cross-Language Data Exchange

TheVine's execution pipeline provides type bridging for supported values between language blocks. The current implementation supports common primitive and collection types, subject to the capabilities and limitations of the underlying GraalVM language implementations.

The implementation does not claim universal type equivalence across all five languages. Language-specific objects, deeply nested structures, and some C/C++ data-transfer cases remain outside the current supported scope.

---

## Running the REPL

TheVine provides a WebSocket-based interactive REPL through the `/ws/repl` endpoint.

Example messages include:

```json
{ "type": "execute", "language": "python", "code": "x = 42" }
{ "type": "execute", "language": "javascript", "code": "console.log(x)" }
{ "type": "reset" }
{ "type": "interrupt" }
```

---

## Evaluation

TheVine was evaluated through functional and performance testing covering:

* `.tv` file parsing and directive recognition
* Dependency resolution
* Cross-language variable exchange
* Supported type bridging
* End-to-end execution scenarios
* Execution-time benchmarking
* Memory-usage measurement

The functional evaluation included nine end-to-end scenarios. Performance benchmarking across five scenarios produced warm execution times ranging from **304 ms to 620 ms** in the tested environment.

These measurements characterize the implementation under the reported test conditions and should not be interpreted as general performance bounds for all hardware or workloads.

---

## Known Limitations

The current implementation has several limitations:

* Some C/C++ programs require native compiler fallback because of limitations affecting particular LLVM/Sulong execution cases.
* Deeply nested objects and some language-specific types are outside the current `convertValue()` type-bridging scope.
* Key-value map structures are not currently transferable to C/C++ through the same mechanism used for supported primitive and collection types.
* Performance and memory characteristics may vary with hardware, workload size, language combinations, and runtime configuration.

---

## Related Publication

Iheagwara, S. E., Leyden, K. E., Otu, G. A., & Ali, Y. S. (2026). *TheVine: A single-file polyglot IDE for declarative cross-language data exchange via GraalVM*. *Journal of Software: Evolution and Process*. Manuscript under review.

---

## Authors

* **Stephen Ebuka Iheagwara**
* **Kopsam Elisha Leyden**
* **Godwin Akong Otu**
* **Yusuf Sahabi Ali**

---

## License

License information will be added to the repository as appropriate.
