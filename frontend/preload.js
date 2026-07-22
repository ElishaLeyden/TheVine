const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  onMenuProjectNew: (callback) => ipcRenderer.on('menu-project-new', callback),
  onMenuProjectOpen: (callback) => ipcRenderer.on('menu-project-open', callback),
  onMenuProjectSave: (callback) => ipcRenderer.on('menu-project-save', callback),
  onMenuFileOpen: (callback) => ipcRenderer.on('menu-file-open', callback),
  onMenuRun: (callback) => ipcRenderer.on('menu-run', callback)
  ,selectProjectFolder: () => ipcRenderer.invoke('dialog:select-project-folder')
  ,selectProjectFile: () => ipcRenderer.invoke('dialog:select-project-file')
});
