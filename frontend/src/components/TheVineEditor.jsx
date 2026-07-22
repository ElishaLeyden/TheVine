import React, { useCallback } from 'react';
import Editor from '@monaco-editor/react';

const THEVINE_LANGUAGE_ID = 'thevine';
let languageRegistered = false;

function registerTheVineLanguage(monaco) {
  if (languageRegistered) return;

  monaco.languages.register({ id: THEVINE_LANGUAGE_ID });

  monaco.languages.setMonarchTokensProvider(THEVINE_LANGUAGE_ID, {
    defaultToken: '',
    tokenizer: {
      root: [
        [/^\s*@(?:python|javascript|java|c|cpp)\b.*/, 'keyword.directive'],
        [/^\s*@(?:export|import)\b.*/, 'predefined'],
        [/\b(?:if|else|for|while|return|class|def|function|const|let|var|public|private|static|void|int|float|double|char|String|new|import|from|try|catch|finally|switch|case|break|continue)\b/, 'keyword'],
        [/\b(?:true|false|null|None)\b/, 'constant'],
        [/"([^"\\]|\\.)*$/, 'string.invalid'],
        [/"/, 'string', '@string'],
        [/'([^'\\]|\\.)*$/, 'string.invalid'],
        [/'/, 'string', '@sstring'],
        [/\/\/.*$/, 'comment'],
        [/#.*$/, 'comment'],
        [/\/\*/, 'comment', '@comment'],
        [/[-+*/=<>!]+/, 'operator'],
        [/[{}()\[\]]/, '@brackets'],
        [/\d+(?:\.\d+)?/, 'number'],
        [/[a-zA-Z_$][\w$]*/, 'identifier']
      ],
      string: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, 'string', '@pop']
      ],
      sstring: [
        [/[^\\']+/, 'string'],
        [/\\./, 'string.escape'],
        [/'/, 'string', '@pop']
      ],
      comment: [
        [/[^/*]+/, 'comment'],
        [/\*\//, 'comment', '@pop'],
        [/[/*]/, 'comment']
      ]
    }
  });

  monaco.languages.setLanguageConfiguration(THEVINE_LANGUAGE_ID, {
    autoClosingPairs: [
      { open: '(', close: ')' },
      { open: '[', close: ']' },
      { open: '{', close: '}' },
      { open: '"', close: '"' },
      { open: "'", close: "'" }
    ],
    surroundingPairs: [
      { open: '(', close: ')' },
      { open: '[', close: ']' },
      { open: '{', close: '}' },
      { open: '"', close: '"' },
      { open: "'", close: "'" }
    ],
    brackets: [
      ['{', '}'],
      ['[', ']'],
      ['(', ')']
    ],
    comments: {
      lineComment: '#',
      blockComment: ['/*', '*/']
    }
  });

  monaco.languages.registerCompletionItemProvider(THEVINE_LANGUAGE_ID, {
    provideCompletionItems: (model, position) => {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn
      };

      return {
        suggestions: [
{
             label: '@export',
             kind: monaco.languages.CompletionItemKind.Snippet,
             insertText: '@export("${1:name}", ${2:value})',
             insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
             documentation: 'Export a value (primitives or objects) from current language block',
             range
           },
           {
             label: '@import',
             kind: monaco.languages.CompletionItemKind.Snippet,
             insertText: '@import("${1:name}")',
             insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
             documentation: 'Import a value from previous blocks or JSON file',
             range
           },
          {
            label: '@python block',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '@python\n${1:# Python code}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range
          },
          {
            label: '@javascript block',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '@javascript\n${1:// JavaScript code}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range
          },
          {
            label: '@java block',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '@java\n${1:// Java code}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range
          },
          {
            label: '@c block',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '@c\n${1:// C code}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range
          },
          {
            label: '@cpp block',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '@cpp\n${1:// C++ code}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            range
          },
          ...['if', 'else', 'for', 'while', 'return', 'class', 'def', 'function', 'const', 'let', 'var', 'public', 'private', 'static', 'void', 'int', 'float', 'double', 'char', 'String', 'import', 'from', 'try', 'catch']
            .map((kw) => ({
              label: kw,
              kind: monaco.languages.CompletionItemKind.Keyword,
              insertText: kw,
              range
            }))
        ]
      };
    }
  });

  monaco.languages.registerFoldingRangeProvider(THEVINE_LANGUAGE_ID, {
    provideFoldingRanges: (model) => {
      const ranges = [];
      let currentStart = null;

for (let line = 1; line <= model.getLineCount(); line += 1) {
          const text = model.getLineContent(line);
          const isDirective = /^\s*@(?:python|javascript|java|c|cpp)\b/.test(text);

          if (isDirective) {
          if (currentStart !== null && line - 1 > currentStart) {
            ranges.push({
              start: currentStart,
              end: line - 1,
              kind: monaco.languages.FoldingRangeKind.Region
            });
          }
          currentStart = line;
        }
      }

      if (currentStart !== null && model.getLineCount() > currentStart) {
        ranges.push({
          start: currentStart,
          end: model.getLineCount(),
          kind: monaco.languages.FoldingRangeKind.Region
        });
      }

      return ranges;
    }
  });

  languageRegistered = true;
}

function TheVineEditor({ code, onChange }) {
  const handleBeforeMount = useCallback((monaco) => {
    registerTheVineLanguage(monaco);
  }, []);

  return (
    <Editor
      height="500px"
      defaultLanguage={THEVINE_LANGUAGE_ID}
      value={code}
      onChange={(value) => onChange(value ?? '')}
      theme="vs-dark"
      beforeMount={handleBeforeMount}
      options={{
        minimap: { enabled: true },
        fontSize: 14,
        wordWrap: 'on',
        automaticLayout: true,
        scrollBeyondLastLine: false,
        folding: true,
        foldingStrategy: 'auto',
        matchBrackets: 'always',
        autoClosingBrackets: 'always',
        autoClosingQuotes: 'always',
        autoSurround: 'languageDefined',
        bracketPairColorization: { enabled: true },
        suggestOnTriggerCharacters: true,
        quickSuggestions: true,
        snippetSuggestions: 'inline',
        tabCompletion: 'on'
      }}
    />
  );
}

export default TheVineEditor;

