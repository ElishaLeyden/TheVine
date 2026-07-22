const { app, BrowserWindow, Menu, dialog, ipcMain } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');

let mainWindow;
let backendProcess;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1000,
    minHeight: 700,
    icon: path.join(__dirname, 'public/icon.ico'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    }
  });

  // Load the app
  if (process.env.NODE_ENV === 'development') {
    mainWindow.loadURL('http://localhost:3000');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, 'dist/index.html'));
  }

  createMenu();
}

function createMenu() {
  const template = [
    {
      label: 'File',
      submenu: [
        {
          label: 'New',
          accelerator: 'CmdOrCtrl+N',
          click: () => mainWindow.webContents.send('menu-project-new')
        },
        {
          label: 'Open Project...',
          accelerator: 'CmdOrCtrl+O',
          click: () => mainWindow.webContents.send('menu-project-open')
        },
        {
          label: 'Save Project',
          accelerator: 'CmdOrCtrl+S',
          click: () => mainWindow.webContents.send('menu-project-save')
        },
        { type: 'separator' },
        {
          label: 'Open File... ',
          accelerator: 'CmdOrCtrl+Shift+O',
          click: () => mainWindow.webContents.send('menu-file-open')
        },
        { type: 'separator' },
        { label: 'Exit', click: () => app.quit() }
      ]
    },
    {
      label: 'Run',
      submenu: [
        {
          label: 'Execute Code',
          accelerator: 'F5',
          click: () => mainWindow.webContents.send('menu-run')
        }
      ]
    }
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

// Start backend
function startBackend() {
  const backendJar = path.join(__dirname, '../backend/target/thevine-backend-1.0.0.jar');
  
  backendProcess = spawn('java', ['-jar', backendJar], {
    stdio: ['pipe', 'pipe', 'pipe']
  });

  backendProcess.stdout.on('data', (data) => {
    console.log(`Backend: ${data}`);
  });

  backendProcess.stderr.on('data', (data) => {
    console.error(`Backend Error: ${data}`);
  });
}

app.whenReady().then(() => {
  ipcMain.handle('dialog:select-project-folder', async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openDirectory', 'createDirectory']
    });
    if (result.canceled || !result.filePaths || result.filePaths.length === 0) {
      return null;
    }
    return result.filePaths[0];
  });

  ipcMain.handle('dialog:select-project-file', async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openFile'],
      filters: [{ name: 'TheVine Project', extensions: ['thevine'] }]
    });
    if (result.canceled || !result.filePaths || result.filePaths.length === 0) {
      return null;
    }
    return result.filePaths[0];
  });

  startBackend();
  createWindow();
});

app.on('window-all-closed', () => {
  if (backendProcess) backendProcess.kill();
  app.quit();
});
