import React, { useState, useEffect, useRef, useCallback } from 'react';

function InteractiveConsole({ code, language = 'python', venvPath, onOutput }) {
  const [ws, setWs] = useState(null);
  const [connected, setConnected] = useState(false);
  const [isRunning, setIsRunning] = useState(false);
  const [output, setOutput] = useState([]);
  const [awaitingInput, setAwaitingInput] = useState(false);
  const [inputValue, setInputValue] = useState('');
  const outputRef = useRef(null);
  const inputRef = useRef(null);

  // Connect to WebSocket
  useEffect(() => {
    const websocket = new WebSocket('ws://localhost:8080/ws/repl');
    
    websocket.onopen = () => {
      console.log('Connected to REPL');
      setConnected(true);
    };
    
    websocket.onclose = () => {
      console.log('Disconnected from REPL');
      setConnected(false);
    };
    
    websocket.onerror = (error) => {
      console.error('WebSocket error:', error);
    };
    
    websocket.onmessage = (event) => {
      const data = JSON.parse(event.data);
      handleMessage(data);
    };
    
    setWs(websocket);
    
    return () => {
      if (websocket) {
        websocket.close();
      }
    };
  }, []);

  const handleMessage = useCallback((data) => {
    switch (data.type) {
      case 'connected':
        setOutput(prev => [...prev, { type: 'system', text: data.message }]);
        break;
      case 'output':
        if (data.stdout) {
          setOutput(prev => [...prev, { type: 'stdout', text: data.stdout }]);
        }
        if (data.stderr) {
          setOutput(prev => [...prev, { type: 'stderr', text: data.stderr }]);
        }
        if (data.result && data.result !== 'null') {
          setOutput(prev => [...prev, { type: 'result', text: data.result }]);
        }
        break;
      case 'input_required':
        setAwaitingInput(true);
        setOutput(prev => [...prev, { type: 'input_prompt', text: data.prompt || 'Input: ' }]);
        setTimeout(() => inputRef.current?.focus(), 0);
        break;
      case 'execution_result':
        setIsRunning(false);
        break;
      case 'execution_complete':
        setIsRunning(false);
        break;
      case 'error':
        setOutput(prev => [...prev, { type: 'error', text: data.message }]);
        setIsRunning(false);
        break;
      case 'interrupted':
        setOutput(prev => [...prev, { type: 'system', text: '⏹️ ' + data.message }]);
        setIsRunning(false);
        break;
      default:
        console.log('Unknown message type:', data.type);
    }
    
    // Scroll to bottom
    outputRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  const executeCode = useCallback(() => {
    if (ws && connected && !isRunning) {
      setIsRunning(true);
      setOutput([]);
      ws.send(JSON.stringify({
        action: 'execute',
        code: code,
        language: language,
        venvPath: venvPath || null
      }));
    }
  }, [ws, connected, isRunning, code, language, venvPath]);

  const sendInput = useCallback((value) => {
    if (ws && connected) {
      ws.send(JSON.stringify({
        action: 'input',
        value: value
      }));
      setOutput(prev => [...prev, { type: 'input', text: value }]);
      setAwaitingInput(false);
      setInputValue('');
    }
  }, [ws, connected]);

  const handleInputSubmit = (e) => {
    e.preventDefault();
    sendInput(inputValue);
  };

  const handleInterrupt = useCallback(() => {
    if (ws && connected) {
      ws.send(JSON.stringify({ action: 'interrupt' }));
    }
  }, [ws, connected]);

  const handleReset = useCallback(() => {
    if (ws && connected) {
      ws.send(JSON.stringify({ action: 'reset' }));
      setOutput([]);
    }
  }, [ws, connected]);

  // Auto-scroll output
  useEffect(() => {
    outputRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [output]);

  return (
    <div style={{
      background: '#1e1e1e',
      border: '1px solid #333',
      borderRadius: '6px',
      display: 'flex',
      flexDirection: 'column',
      height: '400px'
    }}>
      {/* Header */}
      <div style={{
        padding: '8px 12px',
        borderBottom: '1px solid #333',
        background: '#252526',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <span style={{ color: '#ccc' }}>🖥️ Interactive Console</span>
        <div style={{ display: 'flex', gap: '8px' }}>
          <span style={{ 
            fontSize: '10px', 
            color: connected ? '#4CAF50' : '#F48771',
            padding: '2px 6px',
            background: connected ? '#2d4a2d' : '#4a2d2d',
            borderRadius: '3px'
          }}>
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </div>
      </div>
      
      {/* Output area */}
      <div style={{
        flex: 1,
        overflow: 'auto',
        padding: '8px 12px',
        fontFamily: 'Consolas, "Courier New", monospace',
        fontSize: '13px'
      }}>
        {output.map((line, i) => (
          <div key={i} style={{ 
            color: line.type === 'stderr' ? '#F48771' : 
                   line.type === 'error' ? '#F48771' :
                   line.type === 'result' ? '#DCDCAA' :
                   line.type === 'input' ? '#9CDCFE' :
                   line.type === 'input_prompt' ? '#4EC9B0' :
                   '#9CDCFE',
            whiteSpace: 'pre-wrap',
            marginBottom: '2px'
          }}>
            {line.type === 'input_prompt' ? '' : line.type === 'input' ? '> ' : ''}
            {line.text}
          </div>
        ))}
        <div ref={outputRef} />
      </div>
      
      {/* Input area (when awaiting input) */}
      {awaitingInput && (
        <form onSubmit={handleInputSubmit} style={{
          padding: '8px 12px',
          borderTop: '1px solid #333',
          display: 'flex',
          gap: '8px',
          background: '#252526'
        }}>
          <span style={{ color: '#4EC9B0' }}>{'>'}</span>
          <input
            ref={inputRef}
            type="text"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            autoFocus
            style={{
              flex: 1,
              background: 'transparent',
              border: 'none',
              color: '#d4d4d4',
              fontFamily: 'inherit',
              fontSize: 'inherit',
              outline: 'none'
            }}
          />
          <button type="submit" style={{
            padding: '2px 8px',
            background: '#094771',
            border: 'none',
            borderRadius: '3px',
            color: '#9CDCFE',
            cursor: 'pointer'
          }}>
            Send
          </button>
        </form>
      )}
      
      {/* Action buttons */}
      <div style={{
        padding: '8px 12px',
        borderTop: '1px solid #333',
        display: 'flex',
        gap: '8px',
        background: '#252526'
      }}>
        {isRunning ? (
          <button onClick={handleInterrupt} style={{
            padding: '4px 12px',
            background: '#c42b1c',
            border: 'none',
            borderRadius: '3px',
            color: '#fff',
            cursor: 'pointer'
          }}>
            ■ Stop
          </button>
        ) : (
          <button onClick={executeCode} disabled={!connected} style={{
            padding: '4px 12px',
            background: connected ? '#094771' : '#333',
            border: 'none',
            borderRadius: '3px',
            color: connected ? '#9CDCFE' : '#666',
            cursor: connected ? 'pointer' : 'not-allowed'
          }}>
            ▶ Run
          </button>
        )}
        <button onClick={handleReset} disabled={!connected} style={{
          padding: '4px 12px',
          background: '#333',
          border: 'none',
          borderRadius: '3px',
          color: '#d4d4d4',
          cursor: 'connected' ? 'pointer' : 'not-allowed'
        }}>
          Reset
        </button>
      </div>
    </div>
  );
}

export default InteractiveConsole;
