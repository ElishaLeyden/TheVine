import React, { useEffect, useMemo, useState } from 'react'
import TheVineEditor from './components/TheVineEditor'
import Terminal from './components/Terminal'
import InteractiveConsole from './components/InteractiveConsole'

function App() {
  const [code, setCode] = useState("")

  const [output, setOutput] = useState(null)
  const [loading, setLoading] = useState(false)
  const [isRunning, setIsRunning] = useState(false)
  const [project, setProject] = useState(null)
  const [currentController, setCurrentController] = useState(null)
  const [tree, setTree] = useState([])
  const [openFiles, setOpenFiles] = useState([])
  const [activeFile, setActiveFile] = useState(null)
  const [recentProjects, setRecentProjects] = useState([])
  const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, dirPath: null })
  const [pythonVenvPath, setPythonVenvPath] = useState('')
  const [showTerminal, setShowTerminal] = useState(false)
  const [showConsole, setShowConsole] = useState(false)

  const projectName = useMemo(() => {
    if (!project || !project.metadata) return 'No Project Opened'
    return project.metadata.name || 'TheVine Project'
  }, [project])

  useEffect(() => {
    console.log('Electron bridge available:', !!window.electronAPI)
    const savedRecent = localStorage.getItem('thevine.recentProjects')
    if (savedRecent) {
      try {
        setRecentProjects(JSON.parse(savedRecent))
      } catch (_) {}
    }

    if (window.electronAPI) {
      window.electronAPI.onMenuProjectNew(handleCreateProject)
      window.electronAPI.onMenuProjectOpen(handleOpenProject)
      window.electronAPI.onMenuProjectSave(handleSaveProject)
      window.electronAPI.onMenuFileOpen(handleMenuOpenFile)
      window.electronAPI.onMenuRun(handleRun)
    }
  }, [])

  useEffect(() => {
    const hide = () => setContextMenu({ visible: false, x: 0, y: 0, dirPath: null })
    if (contextMenu.visible) {
      window.addEventListener('click', hide, { once: true })
    }
    return () => window.removeEventListener('click', hide)
  }, [contextMenu.visible])

  const updateRecentProjects = (projectPath) => {
    if (!projectPath) return
    const next = [projectPath, ...recentProjects.filter(p => p !== projectPath)].slice(0, 10)
    setRecentProjects(next)
    localStorage.setItem('thevine.recentProjects', JSON.stringify(next))
  }

  const syncProjectMetadata = async (nextOpenFiles, nextCurrentFile) => {
    if (!project) return
    const metadata = {
      ...(project.metadata || {}),
      openFiles: nextOpenFiles,
      currentFile: nextCurrentFile
    }
    await fetch('/api/projects/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectPath: project.projectPath, metadata })
    })
    setProject(prev => ({ ...prev, metadata }))
  }

  async function handleCreateProject() {
    try {
      if (!window.electronAPI?.selectProjectFolder) {
        throw new Error('Electron bridge unavailable. Ensure Electron app is opened (not browser tab), then restart electron-dev.')
      }
      const folder = await window.electronAPI.selectProjectFolder()
      if (!folder) return

      const name = prompt('Project name:', 'TheVine Project') || 'TheVine Project'
      const response = await fetch('/api/projects/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectPath: folder, name })
      })
      const data = await response.json()
      if (!response.ok || data.error) throw new Error(data.error || 'Failed to create project')

      setProject(data)
      setTree(data.tree || [])
      setOpenFiles([])
      setActiveFile(null)
      setCode("")
      updateRecentProjects(data.projectPath)
    } catch (error) {
      setOutput(`Error: ${error.message}`)
    }
  }

  async function handleOpenProject() {
    try {
      if (!window.electronAPI?.selectProjectFolder) {
        throw new Error('Electron bridge unavailable. Ensure Electron app is opened (not browser tab), then restart electron-dev.')
      }
      const projectPath = await window.electronAPI.selectProjectFolder()
      if (!projectPath) return

      await openProjectByPath(projectPath)
    } catch (error) {
      setOutput(`Error: ${error.message}`)
    }
  }

  const openProjectByPath = async (projectPath) => {
    const response = await fetch('/api/projects/open', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectPath })
    })
    const data = await response.json()
    if (!response.ok || data.error) throw new Error(data.error || 'Failed to open project')

    setProject(data)
    setTree(data.tree || [])
    const metadataOpen = (data.metadata?.openFiles || []).filter(Boolean)
    setOpenFiles(metadataOpen)
    const current = data.metadata?.currentFile || metadataOpen[0] || null
    setActiveFile(current)

    if (current) {
      const contentResp = await fetch(`/api/projects/file?projectPath=${encodeURIComponent(data.projectPath)}&filePath=${encodeURIComponent(current)}`)
      const contentData = await contentResp.json()
      if (contentResp.ok && !contentData.error) {
        setCode(contentData.content || '')
      }
    } else {
      setCode("")
    }

    updateRecentProjects(data.projectPath)
  }

  async function handleSaveProject() {
    try {
      if (!project) {
        setOutput('Open a project first.')
        return
      }

      // Determine target path for save (Save vs Save As)
      let targetPath = activeFile
      if (!targetPath) {
        if (window.electronAPI?.selectSaveFile) {
          const absFile = await window.electronAPI.selectSaveFile(project.projectPath)
          if (!absFile) { setOutput('Save canceled.'); return }

          const normalize = (p) => String(p || '').replace(/\\/g, '/').replace(/\/+$/g, '')
          const root = normalize(project.projectPath)
          const abs = normalize(absFile)
          const prefix = `${root}/`
          if (!abs.toLowerCase().startsWith(prefix.toLowerCase())) {
            throw new Error('Selected save location must be inside the currently opened project folder.')
          }
          targetPath = abs.slice(prefix.length)
        } else {
          const rel = prompt('Save as (relative to project), e.g., src/main or src/main.tv:')
          if (!rel) { setOutput('Save canceled.'); return }
          targetPath = rel
        }
      }

      // Write editor content to disk (.tv is enforced by backend when no extension)
      const response = await fetch('/api/projects/file', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          projectPath: project.projectPath,
          filePath: targetPath,
          content: code
        })
      })
      const data = await response.json()
      if (!response.ok || data.error) throw new Error(data.error || 'Failed to save file')

      const finalPath = data.filePath || targetPath
      if (!activeFile || activeFile !== finalPath) {
        setActiveFile(finalPath)
      }
      const nextOpen = [...new Set([...openFiles, finalPath])]
      setOpenFiles(nextOpen)

      await syncProjectMetadata(nextOpen, finalPath)
      await refreshTree()
      setOutput('Saved file and project metadata.')
    } catch (error) {
      setOutput(`Error: ${error.message}`)
    }
  }

  async function handleMenuOpenFile() {
    try {
      if (!project) {
        setOutput('Open a project first.')
        return
      }

      if (window.electronAPI?.selectAnyFile) {
        const selectedFile = await window.electronAPI.selectAnyFile(project.projectPath)
        if (!selectedFile) return

        const normalize = (p) => String(p || '').replace(/\\/g, '/').replace(/\/+$/g, '')
        const projectRoot = normalize(project.projectPath)
        const selected = normalize(selectedFile)

        const prefix = `${projectRoot}/`
        if (!selected.toLowerCase().startsWith(prefix.toLowerCase())) {
          throw new Error('Selected file must be inside the currently opened project folder.')
        }

        const relativeFile = selected.slice(prefix.length)
        await openFile(relativeFile)
        return
      }

      const filePath = prompt('Relative file path inside project (example: src/main.py):')
      if (!filePath) return
      await openFile(filePath)
    } catch (error) {
      setOutput(`Error: ${error.message}`)
    }
  }

  const refreshTree = async () => {
    try {
      if (!project) {
        setOutput('Open a project first.')
        return
      }

      const response = await fetch(`/api/projects/tree?projectPath=${encodeURIComponent(project.projectPath)}`)
      const data = await response.json()
      if (!response.ok || data.error) {
        throw new Error(data.error || 'Failed to refresh file explorer')
      }

      setTree(data.tree || [])
      setOutput('File explorer refreshed.')
    } catch (error) {
      setOutput(`Error: ${error.message}`)
    }
  }

  const openFile = async (filePath) => {
    if (!project) return
    const response = await fetch(`/api/projects/file?projectPath=${encodeURIComponent(project.projectPath)}&filePath=${encodeURIComponent(filePath)}`)
    const data = await response.json()

    if (!response.ok || data.error) {
      const create = confirm(`File '${filePath}' does not exist. Create it?`)
      if (!create) return

      await fetch('/api/projects/file', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectPath: project.projectPath, filePath, content: '' })
      })
      setCode('')
      setActiveFile(filePath)
      const nextOpen = [...new Set([...openFiles, filePath])]
      setOpenFiles(nextOpen)
      await syncProjectMetadata(nextOpen, filePath)
      await refreshTree()
      return
    }

    setCode(data.content || '')
    setActiveFile(filePath)
    const nextOpen = [...new Set([...openFiles, filePath])]
    setOpenFiles(nextOpen)
    await syncProjectMetadata(nextOpen, filePath)
  }

  const onContextMenuDir = (e, dirPath) => {
    e.preventDefault()
    if (!project) return
    const x = e.clientX || 0
    const y = e.clientY || 0
    setContextMenu({ visible: true, x, y, dirPath })
  }

  const createNewFileInDir = async () => {
    try {
      if (!project || !contextMenu.dirPath) return
      let name = prompt('New file name (e.g., file.tv or file):')
      if (!name) return
      // normalize join; support project root (empty dirPath)
      const base = contextMenu.dirPath === '' ? '' : (contextMenu.dirPath.endsWith('/') ? contextMenu.dirPath : contextMenu.dirPath + '/')
      const relPath = base + name

      const resp = await fetch('/api/projects/file', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectPath: project.projectPath, filePath: relPath, content: '' })
      })
      const data = await resp.json()
      if (!resp.ok || data.error) throw new Error(data.error || 'Failed to create file')

      const createdPath = data.filePath || relPath
      await refreshTree()
      await openFile(createdPath)
      setContextMenu({ visible: false, x: 0, y: 0, dirPath: null })
    } catch (err) {
      setOutput(`Error: ${err.message}`)
    }
  }

  const handleRun = async () => {
    setLoading(true)
    setIsRunning(true)
    setOutput('Running...')
    
    // Create abort controller for stopping
    const controller = new AbortController()
    setCurrentController(controller)
    
    try {
      const response = await fetch('/api/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          code,
          projectPath: project?.projectPath || null,
          context: {
            pythonVenvPath: pythonVenvPath || null
          }
        }),
        signal: controller.signal
      })
      
      const data = await response.json()
      setOutput(data)
    } catch (error) {
      if (error.name === 'AbortError') {
        setOutput('⏹️ Execution stopped by user')
      } else {
        setOutput('Error: ' + error.message)
      }
    } finally {
      setLoading(false)
      setIsRunning(false)
      setCurrentController(null)
    }
  }
  
  const handleStop = () => {
    if (currentController) {
      currentController.abort()
      setOutput('⏹️ Stopping...')
    }
  }

  const renderTree = (nodes) => {
    if (!nodes || nodes.length === 0) return null
    return (
      <ul style={{ listStyle: 'none', paddingLeft: '14px', margin: 0 }}>
        {nodes.map((n) => (
          <li key={n.path} style={{ margin: '3px 0' }}>
            {n.type === 'directory' ? (
              <div onContextMenu={(e) => onContextMenuDir(e, n.path)}>
                <span style={{ color: '#9cdcfe', cursor: 'context-menu' }}>📁 {n.name}</span>
                {renderTree(n.children || [])}
              </div>
            ) : (
              <button
                onClick={() => openFile(n.path)}
                style={{
                  border: 'none',
                  background: 'transparent',
                  color: activeFile === n.path ? '#4CAF50' : '#d4d4d4',
                  cursor: 'pointer',
                  padding: 0,
                  textAlign: 'left'
                }}
              >
                📄 {n.name}
              </button>
            )}
          </li>
        ))}
      </ul>
    )
  }

  return (
    <div style={{ padding: '16px', background: '#1e1e1e', minHeight: '100vh', color: 'white' }}>
      <h1 style={{ color: '#4CAF50', marginTop: 0 }}>🌿 TheVine IDE</h1>

      <div style={{ marginBottom: '12px', display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
        <button onClick={handleCreateProject}>New Project</button>
        <button onClick={handleOpenProject}>Open Project</button>
        <button onClick={handleSaveProject}>Save Project</button>
        <button onClick={refreshTree} disabled={!project}>Refresh Explorer</button>
        <button onClick={handleMenuOpenFile} disabled={!project}>Open File</button>
        {isRunning ? (
          <button onClick={handleStop} style={{ background: '#c42b1c', color: '#fff' }}>
            ■ Stop
          </button>
        ) : (
          <button onClick={handleRun} disabled={loading}>
            🚀 Run Code
          </button>
        )}
      </div>

      {/* Python Virtual Environment Path */}
      <div style={{ marginBottom: '12px', display: 'flex', gap: '8px', alignItems: 'center' }}>
        <label style={{ color: '#9cdcfe', fontSize: '13px' }}>🐍 Python venv:</label>
        <input
          type="text"
          value={pythonVenvPath}
          onChange={(e) => setPythonVenvPath(e.target.value)}
          placeholder="/path/to/venv (optional)"
          style={{
            flex: 1,
            padding: '4px 8px',
            background: '#1e1e1e',
            border: '1px solid #333',
            borderRadius: '4px',
            color: '#d4d4d4',
            fontSize: '13px'
          }}
        />
        <button 
          onClick={() => setShowTerminal(!showTerminal)}
          style={{
            padding: '4px 12px',
            background: showTerminal ? '#094771' : '#333',
            border: 'none',
            borderRadius: '3px',
            color: '#9CDCFE',
            cursor: 'pointer'
          }}
        >
          🖥️ Terminal {showTerminal ? '▲' : '▼'}
        </button>
        <button 
          onClick={() => setShowConsole(!showConsole)}
          style={{
            padding: '4px 12px',
            background: showConsole ? '#094771' : '#333',
            border: 'none',
            borderRadius: '3px',
            color: '#9CDCFE',
            cursor: 'pointer'
          }}
        >
          💬 Console {showConsole ? '▲' : '▼'}
        </button>
      </div>

      {/* Main Layout: Editor + Optional Side Panel */}
      <div style={{ display: 'grid', gridTemplateColumns: showTerminal || showConsole ? '1fr 380px' : '1fr', gap: '16px' }}>
        
        {/* Main Content */}
        <div>
          <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: '16px' }}>
            <aside style={{ border: '1px solid #333', borderRadius: '6px', padding: '10px', minHeight: '520px' }}>
              <h3 style={{ margin: '0 0 8px 0', color: '#4CAF50' }}>Project</h3>
              <div style={{ fontSize: '13px', color: '#d4d4d4', marginBottom: '10px' }}>{projectName}</div>
              {project && (
                <div onContextMenu={(e) => onContextMenuDir(e, '')} style={{ marginBottom: '6px' }}>
                  <span style={{ color: '#9cdcfe', cursor: 'context-menu' }}>📁 {projectName} (root)</span>
                </div>
              )}
              {project && <div style={{ fontSize: '12px', color: '#888', marginBottom: '10px' }}>{project.projectPath}</div>}

              <h4 style={{ margin: '10px 0 6px 0', color: '#9cdcfe' }}>File Explorer</h4>
              <div style={{ maxHeight: '280px', overflowY: 'auto', border: '1px solid #2d2d2d', padding: '6px' }}>
                {project ? renderTree(tree) : <div style={{ color: '#999' }}>Open a project to view files.</div>}
              </div>

              <h4 style={{ margin: '10px 0 6px 0', color: '#9cdcfe' }}>Recent Projects</h4>
              <div style={{ maxHeight: '140px', overflowY: 'auto' }}>
                {recentProjects.length === 0 && <div style={{ color: '#999' }}>No recent projects.</div>}
                {recentProjects.map((p) => (
                  <button
                    key={p}
                    onClick={() => openProjectByPath(p).catch(err => setOutput(`Error: ${err.message}`))}
                    style={{
                      display: 'block',
                      width: '100%',
                      textAlign: 'left',
                      marginBottom: '4px',
                      background: '#252526',
                      color: '#d4d4d4',
                      border: '1px solid #333',
                      borderRadius: '4px',
                      padding: '6px',
                      cursor: 'pointer'
                    }}
                  >
                    {p}
                  </button>
                ))}
              </div>
            </aside>

            <section>
              <div style={{ marginBottom: '8px', color: '#9cdcfe' }}>
                Active file: {activeFile || 'Untitled'}
              </div>
              <div style={{ border: '1px solid #333' }}>
                <TheVineEditor code={code} onChange={setCode} />
              </div>

              <div style={{
                marginTop: '16px',
                background: '#2d2d2d',
                padding: '15px',
                borderRadius: '4px',
                border: '1px solid #404040'
              }}>
                <h3 style={{ color: '#4CAF50', marginBottom: '10px' }}>Output:</h3>
                {typeof output === 'string' && (
                  <pre style={{ color: '#d4d4d4', fontFamily: 'Consolas, monospace', whiteSpace: 'pre-wrap' }}>{output}</pre>
                )}
                {output && typeof output === 'object' && output.success && (
                  <div>
                    <pre style={{ color: '#d4d4d4', fontFamily: 'Consolas, monospace', whiteSpace: 'pre-wrap' }}>{output.output}</pre>
                  </div>
                )}
                {output && typeof output === 'object' && !output.success && (
                  <pre style={{ color: '#f48771' }}>{output.error || 'Execution failed'}</pre>
                )}
              </div>
            </section>
          </div>
        </div>

        {/* Side Panel: Terminal and Console */}
        {(showTerminal || showConsole) && (
          <aside style={{ 
            display: 'flex', 
            flexDirection: 'column', 
            gap: '12px',
            maxHeight: '600px',
            overflow: 'hidden'
          }}>
            {showTerminal && <Terminal projectPath={project?.projectPath} />}
            {showConsole && (
              <InteractiveConsole 
                code={code}
                venvPath={pythonVenvPath}
              />
            )}
          </aside>
        )}
      </div>

      {contextMenu.visible && (
        <div
          style={{
            position: 'fixed',
            top: contextMenu.y,
            left: contextMenu.x,
            background: '#252526',
            color: '#d4d4d4',
            border: '1px solid #3c3c3c',
            borderRadius: 4,
            zIndex: 1000,
            minWidth: 160,
            boxShadow: '0 2px 8px rgba(0,0,0,0.5)'
          }}
        >
          <button
            onClick={createNewFileInDir}
            style={{
              width: '100%',
              background: 'transparent',
              color: '#d4d4d4',
              padding: '8px 12px',
              border: 'none',
              textAlign: 'left',
              cursor: 'pointer'
            }}
          >
            ➕ New File
          </button>
        </div>
      )}
    </div>
  )
}

export default App
