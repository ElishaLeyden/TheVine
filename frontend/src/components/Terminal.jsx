import React, { useState, useRef, useEffect } from 'react';

function Terminal({ projectPath }) {
  const [history, setHistory] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [cwd, setCwd] = useState(projectPath || '');
  const bottomRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [history]);

  useEffect(() => {
    if (projectPath) {
      setCwd(projectPath);
    }
  }, [projectPath]);

  const executeCommand = async (cmd) => {
    if (!cmd.trim()) return;
    
    setLoading(true);
    
    // Add command to history
    setHistory(prev => [...prev, { type: 'command', text: cmd }]);
    const currentInput = input;
    setInput('');
    
    try {
      const response = await fetch('/api/terminal/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          command: cmd,
          workingDir: cwd || projectPath
        })
      });
      
      const data = await response.json();
      
      if (data.success) {
        setHistory(prev => [...prev, { type: 'output', text: data.output || '(no output)' }]);
      } else {
        // Show both output and error for debugging
        let errorMsg = data.error || 'Command failed';
        if (data.output) {
          errorMsg = data.output;
        }
        setHistory(prev => [...prev, { type: 'error', text: errorMsg }]);
      }
      
      // Update cwd if command changed directory
      if (cmd.startsWith('cd ') && data.success) {
        const newPath = cmd.slice(3).trim().replace(/"/g, '');
        if (newPath.startsWith('/') || newPath.match(/^[A-Za-z]:/)) {
          setCwd(newPath);
        }
      }
    } catch (error) {
      setHistory(prev => [...prev, { type: 'error', text: 'Error: ' + error.message }]);
    } finally {
      setLoading(false);
      setInput(currentInput);
      inputRef.current?.focus();
    }
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    executeCommand(input);
  };

  const handleFocus = () => {
    inputRef.current?.focus();
  };

  return (
    <div style={{
      background: '#1e1e1e',
      border: '1px solid #333',
      borderRadius: '6px',
      fontFamily: 'Consolas, "Courier New", monospace',
      fontSize: '13px',
      height: '300px',
      display: 'flex',
      flexDirection: 'column'
    }}
    onClick={handleFocus}
    >
      {/* Header */}
      <div style={{
        padding: '8px 12px',
        borderBottom: '1px solid #333',
        background: '#252526',
        color: '#ccc',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        cursor: 'pointer'
      }}>
        <span>🖥️ Terminal</span>
        <span style={{ fontSize: '11px', color: '#888' }}>cwd: {cwd || 'none'}</span>
      </div>
      
      {/* Output area */}
      <div style={{
        flex: 1,
        overflow: 'auto',
        padding: '8px 12px',
        color: '#d4d4d4',
        cursor: 'text'
      }}>
        {history.map((entry, i) => (
          <div key={i} style={{ marginBottom: '4px' }}>
            {entry.type === 'command' && (
              <div>
                <span style={{ color: '#4EC9B0' }}>{cwd || '$'}</span>{' '}
                <span style={{ color: '#DCDCAA' }}>{entry.text}</span>
              </div>
            )}
            {entry.type === 'output' && (
              <pre style={{ margin: '2px 0', whiteSpace: 'pre-wrap', color: '#9CDCFE' }}>
                {entry.text}
              </pre>
            )}
            {entry.type === 'error' && (
              <pre style={{ margin: '2px 0', whiteSpace: 'pre-wrap', color: '#F48771' }}>
                {entry.text}
              </pre>
            )}
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      
      {/* Input */}
      <form onSubmit={handleSubmit} style={{
        padding: '8px 12px',
        borderTop: '1px solid #333',
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        background: '#1e1e1e'
      }}>
        <span style={{ color: '#4EC9B0', userSelect: 'none' }}>{cwd || '$'}</span>
        <input
          ref={inputRef}
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type command and press Enter..."
          disabled={loading}
          autoFocus
          style={{
            flex: 1,
            background: 'transparent',
            border: 'none',
            color: '#d4d4d4',
            fontFamily: 'inherit',
            fontSize: 'inherit',
            outline: 'none',
            caretColor: '#d4d4d4'
          }}
        />
        {loading && <span style={{ color: '#888' }}>running...</span>}
      </form>
    </div>
  );
}

export default Terminal;
