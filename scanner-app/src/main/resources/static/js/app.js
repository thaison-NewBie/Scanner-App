// ── STATE ─────────────────────────────────────────────────────
const state = {
  images:       { 1: null, 2: null, 3: null, 4: null },
  rotations:    { 1: 0,    2: 0,    3: 0,    4: 0    },
  imageFolders: { 3: '',   4: '' },
  lastImageFolder: '',
  excelFolder:  '',
  excelLoaded:  false,
  savePath:     '',
  capturesRoot: '',
  // Dùng khi xuất file
  pendingExport: null,    // { formData, serialNumber }
  selectedSN:    '',
  // Lưu tên file ảnh vừa chọn để gợi ý tên file tiếp theo
  lastImageName: '',
  // Vị trí và tên file dùng chung riêng cho bộ ảnh 1 ↔ 2
  lastImageFolder12: '',
  lastImageName12: '',
};

// ── DOM REFS ──────────────────────────────────────────────────
const $ = id => document.getElementById(id);

function rememberPickerPath(path) {
  state.lastImageFolder = path;
}

function resetBrowserMemory() {
  state.lastImageFolder = '';
  state.lastImageFolder12 = '';
  state.lastImageName12 = '';
  state.lastImageName = '';
  try {
    localStorage.removeItem('scanner.lastFolder34');
    localStorage.removeItem('scanner.lastFolder12');
    localStorage.removeItem('scanner.lastName12');
  } catch (_) {}
}

// ── INIT ──────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  resetBrowserMemory();
  await resetServerSession();
  try { await loadStorageConfig(); }
  catch (error) { addLog('❌ Không đọc được cấu hình thư mục: ' + error.message); }
  setupExcelUpload();
  setupImageInputs();
  ['deviceName','productCode','poNumber','serialManual'].forEach(id => {
    $(id)?.addEventListener('input', updateProgress);
  });
  document.querySelectorAll('input[name=reportType]').forEach(r => {
    r.addEventListener('change', updateProgress);
    r.addEventListener('change', updateRepairNotesState);
  });
  updateProgress();
  updateRepairNotesState();
});

async function resetServerSession() {
  try {
    await fetch('/api/session/reset', { method: 'POST' });
  } catch (error) { console.warn('Không reset được phiên server', error); }
}

// Reset ở lần mở trang kế tiếp; không gửi beacon trễ có thể xóa phiên vừa tải lại.

// ── GHI CHÚ — chỉ bật khi Cháy Nổ hoặc Sau Sửa Chữa ─────────
function updateRepairNotesState() {
  const type     = document.querySelector('input[name=reportType]:checked')?.value;
  const notes    = $('repairNotes');
  const group    = notes?.closest('.form-group');
  const disabled = (type === 'TRUOC_SCC');
  if (!notes) return;
  notes.disabled    = disabled;
  notes.placeholder = disabled ? '⛔ Không áp dụng cho Trước Sửa Chữa' : 'Nhập ghi chú/lỗi đã sửa...';
  notes.style.opacity = disabled ? '0.35' : '1';
  notes.style.cursor  = disabled ? 'not-allowed' : '';
  if (group) group.style.opacity = disabled ? '0.5' : '1';
  if (disabled) notes.value = '';
}


// ── EXCEL UPLOAD ──────────────────────────────────────────────
function setupExcelUpload() {
  const input = $('excelFile');
  input.addEventListener('change', async () => {
    if (!input.files[0]) return;
    showLoading('Đang đọc file Excel...');
    const form = new FormData();
    form.append('file', input.files[0]);
    try {
      const res  = await fetch('/api/excel/upload', { method: 'POST', body: form });
      const data = await res.json();
      if (data.success) {
        applyLoadedExcel(data, 'điện thoại');
      } else {
        addLog('❌ ' + (data.error || 'Không đọc được file'));
      }
    } catch (e) {
      addLog('❌ Lỗi: ' + e.message);
    } finally { hideLoading(); updateProgress(); }
  });
}

function applyLoadedExcel(data, source = 'máy chủ') {
  state.excelLoaded = true;
  $('excelStatus').textContent  = `✓ ${data.filename} — ${data.totalValues} SN`;
  $('excelStatus').className    = 'status-text status-ok';
  $('snStatus').className       = 'sn-badge sn-loaded';
  $('snStatusText').textContent = `Excel: ${data.totalValues} SN`;
  addLog(`✔ Excel (${source}): ${data.filename} — ${data.totalValues} SN (${data.rowCount} dòng)`);
  if (data.sample?.length) addLog(`📋 Mẫu: ${data.sample.join(', ')}...`);
  // Preload SN list cho cả desktop/mobile; request được dùng lại khi xuất.
  allSNList = [];
  _snListPromise = null;
  loadExcelSNList().catch(error => addLog('⚠ Không preload được danh sách SN: ' + error.message));
}

function closeServerExcelPicker() {
  _excelBrowseRequest++;
  $('serverExcelModal').classList.add('hidden');
}

async function openServerExcelPicker() {
  $('serverExcelModal').classList.remove('hidden');
  $('excelManualPathInput').value = '';
  $('confirmExcelBtn').disabled = true;
  $('selectedExcelPathDisplay').textContent = 'Chọn một file .xlsx hoặc .xls bên dưới';
  _selectedExcelPath = '';
  await browseServerExcel(state.excelFolder || '');
}

let _selectedExcelPath = '';
let _excelBrowseRequest = 0;

function formatServerFileSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

async function browseServerExcel(path) {
  const request = ++_excelBrowseRequest;
  const list = $('serverExcelList');
  list.innerHTML = '<div class="folder-loading">Đang tải thư mục trên máy chủ...</div>';
  $('confirmExcelBtn').disabled = true;
  _selectedExcelPath = '';
  try {
    const response = await fetch('/api/excel/browse?path=' + encodeURIComponent(path));
    const data = await response.json();
    if (!response.ok || !data.success) throw new Error(data.error || 'Không tải được danh sách Excel');
    if (request !== _excelBrowseRequest) return;

    $('excelModalPathText').textContent = data.current ? ' > ' + data.current : '';
    state.excelFolder = data.current || '';
    list.innerHTML = '';

    if (data.warning) {
      const warning = document.createElement('div');
      warning.className = 'folder-empty';
      warning.textContent = '⚠ ' + data.warning;
      list.appendChild(warning);
    }

    if (data.current && data.parent) {
      const up = document.createElement('div');
      up.className = 'folder-item folder-up';
      up.innerHTML = '<span class="folder-icon" style="margin-right:8px">⬆️</span>.. (Lên một cấp)';
      up.onclick = () => browseServerExcel(data.parent);
      list.appendChild(up);
    }

    data.entries?.forEach(entry => {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'folder-item';
      const name = document.createElement('span');
      name.textContent = `${entry.type === 'drive' ? '💽' : '📁'} ${entry.name} — Chạm để mở ➔`;
      item.appendChild(name);
      item.onclick = () => browseServerExcel(entry.path);
      list.appendChild(item);
    });

    (data.files || []).forEach(file => {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'folder-item';
      const name = document.createElement('span');
      name.textContent = `📊 ${file.name}`;
      const meta = document.createElement('span');
      meta.className = 'folder-item-meta';
      meta.textContent = formatServerFileSize(file.size || 0);
      item.append(name, meta);
      item.onclick = () => {
        document.querySelectorAll('#serverExcelList .folder-item').forEach(node => node.classList.remove('selected'));
        item.classList.add('selected');
        _selectedExcelPath = file.path;
        $('selectedExcelPathDisplay').textContent = 'Đã chọn: ' + file.path;
        $('confirmExcelBtn').disabled = false;
      };
      list.appendChild(item);
    });

    if (!data.current && !data.entries?.length) {
      list.innerHTML = '<div class="folder-empty">Không tìm thấy ổ đĩa nào trên máy chủ.</div>';
    } else if (data.current && !data.entries?.length && !data.files?.length) {
      const empty = document.createElement('div');
      empty.className = 'folder-empty';
      empty.textContent = 'Không có folder hoặc file .xlsx/.xls trong thư mục này.';
      list.appendChild(empty);
    }
  } catch (error) {
    if (request !== _excelBrowseRequest) return;
    list.innerHTML = '';
    const message = document.createElement('div');
    message.className = 'folder-error';
    message.textContent = `❌ ${error.message}`;
    list.appendChild(message);
  }
}

async function confirmServerExcel() {
  if (!_selectedExcelPath) return;
  showLoading('Đang đọc Excel trên máy chủ...');
  try {
    const response = await fetch('/api/excel/load-file', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: _selectedExcelPath })
    });
    const data = await response.json();
    if (!response.ok || !data.success) throw new Error(data.error || 'Không đọc được Excel');
    applyLoadedExcel(data, 'máy chủ');
    closeServerExcelPicker();
  } catch (error) {
    showNotice('Không đọc được Excel trên máy chủ', error.message);
  } finally {
    hideLoading();
    updateProgress();
  }
}

// ── FOLDER BROWSER — Web-based UI (mobile friendly) ────────────

let _browserTarget = null;
let _selectedFolderPath = '';
let _browseRequest = 0;
let _storageConfig = null;

async function loadStorageConfig() {
  if (_storageConfig) return _storageConfig;
  const response = await fetch('/api/storage/config');
  const config = await response.json();
  if (!response.ok || !config.success) throw new Error('Không kết nối được server');
  _storageConfig = config;
  state.capturesRoot = config.capturesRoot || '';
  const capturesBtn = $('capturesRootBtn');
  if (capturesBtn) capturesBtn.style.display = state.capturesRoot ? '' : 'none';
  const foldersUnrestricted = config.foldersUnrestricted || !config.nasMode;
  $('folderRootButton').textContent = foldersUnrestricted ? '💻 Vị trí máy tính' : '📁 Vị trí NAS';
  if (foldersUnrestricted) $('manualPathInput').placeholder = 'Dán đường dẫn thư mục trên máy';
  return config;
}

/** Mở thẳng thư mục chứa ảnh chụp đã tự lưu trên máy chủ. */
function browseCapturesRoot() {
  if (state.capturesRoot) browseTo(state.capturesRoot);
}

async function openBrowser(target) {
  let config;
  try { config = await loadStorageConfig(); }
  catch (error) { showNotice('Không mở được bộ chọn thư mục', error.message); return; }
  const isLocalWindows = location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.hostname === '::1';
  const rememberedPath = target === 'save'
    ? state.savePath
    : (state.lastImageFolder || state.imageFolders[parseInt(target.replace('folder', ''))]);
  // Explorer trên laptop luôn bắt đầu từ ổ C: khi chưa có vị trí đã chọn.
  // Bộ chọn web bắt đầu từ danh sách ổ đĩa khi app được phép chọn mọi nơi.
  const foldersUnrestricted = config.foldersUnrestricted || !config.nasMode;
  const initial = rememberedPath || (isLocalWindows
    ? 'C:\\'
    : (foldersUnrestricted ? '' : (target === 'save' ? (config.reportsRoot || '') : (config.imagesRoot || ''))));

  // Nếu người dùng đang ngồi trực tiếp trên máy chủ (localhost), dùng hộp thoại Windows gốc cho nhanh
  // Khi dùng trực tiếp trên máy Windows chạy app, mở Explorer gốc như ứng dụng desktop.
  // Điện thoại/máy khác vẫn dùng bộ chọn web.
  if (isLocalWindows) {
    try {
      addLog('📂 Đang mở Explorer Windows...');
      const res = await fetch('/api/native-pick-folder?initial=' + encodeURIComponent(initial));
      const data = await res.json();
      if (!res.ok && !data.cancelled) throw new Error(data.error || 'Windows không mở được hộp thoại chọn thư mục');
      if (data.cancelled) {
        addLog('↩ Đã hủy chọn thư mục');
        return;
      }
      if (data.path) {
        if (target === 'save') {
          $('savePathInput').value = data.path;
          setSavePath(data.path);
        } else {
          const slot = parseInt(target.replace('folder', ''));
          state.imageFolders[slot] = data.path;
          rememberPickerPath(data.path);
          $(target).value = data.path;
          addLog(`📁 Slot ${slot}: Chọn folder ${data.path}`);
        }
      }
    } catch (e) {
      console.error(e);
      addLog('❌ Lỗi mở Native Picker: ' + e.message);
      showNotice('Không mở được Explorer Windows', e.message);
    }
    return;
  }

  // Ngược lại (điện thoại, máy tính khác), dùng giao diện Web Modal
  _browserTarget = target;
  $('manualPathInput').value = '';
  $('manualPathInput').placeholder = target === 'save'
    ? 'Dán đường dẫn thư mục lưu trên máy'
    : 'Dán đường dẫn thư mục ảnh trên máy';
  $('folderModalTitle').textContent = target === 'save' ? '💾 Chọn nơi lưu file Word' : '📂 Chọn folder ảnh';
  $('folderModal').classList.remove('hidden');
  browseTo(initial);
}

function closeBrowser() {
  _browseRequest++;
  $('folderModal').classList.add('hidden');
  _browserTarget = null;
}

async function browseTo(path) {
  const request = ++_browseRequest;
  const purpose = _browserTarget === 'save' ? 'reports' : 'images';
  $('folderList').innerHTML = '<div class="folder-loading">Đang tải...</div>';
  $('confirmFolderBtn').disabled = true;
  _selectedFolderPath = '';
  $('selectedPathDisplay').textContent = 'Đang tải...';

  try {
    const res = await fetch('/api/browse?purpose=' + purpose + '&path=' + encodeURIComponent(path));
    const data = await res.json();
    if (request !== _browseRequest) return;
    if (!data.success) throw new Error(data.error);

    $('modalPathText').textContent = data.current ? ' > ' + data.current : '';
    
    if (data.current) {
      _selectedFolderPath = data.current;
      $('selectedPathDisplay').textContent = 'Đang chọn: ' + data.current;
      $('confirmFolderBtn').disabled = false;
    } else {
      $('selectedPathDisplay').textContent = 'Chọn một thư mục bên dưới';
    }
    
    const list = $('folderList');
    list.innerHTML = '';

    if (data.warning) {
      const warning = document.createElement('div');
      warning.className = 'folder-empty';
      warning.textContent = '⚠ ' + data.warning;
      list.appendChild(warning);
    }

    if (data.current) {
      const up = document.createElement('div');
      up.className = 'folder-item folder-up';
      up.innerHTML = '<span class="folder-icon" style="margin-right:8px">⬆️</span>.. (Lên một cấp)';
      up.onclick = () => browseTo(data.parent);
      list.appendChild(up);
    }

    if (!data.entries || !data.entries.length) {
      const empty = document.createElement('div');
      empty.className = 'folder-empty';
      empty.textContent = 'Không có thư mục con. Có thể chọn thư mục hiện tại.';
      list.appendChild(empty);
      return;
    }

    data.entries.forEach(ent => {
      const item = document.createElement('div');
      item.className = 'folder-item';
      const icon = ent.type === 'drive' ? '💽' : '📁';
      
      const leftDiv = document.createElement('div');
      leftDiv.style.display = 'flex';
      leftDiv.style.alignItems = 'center';
      leftDiv.style.flex = '1';
      leftDiv.style.overflow = 'hidden';
      leftDiv.textContent = `${icon} ${ent.name} — Chạm để mở ➔`;
      leftDiv.onclick = () => browseTo(ent.path);
      
      item.appendChild(leftDiv);

      const selBtn = document.createElement('button');
      selBtn.className = 'btn btn-outline btn-sm';
      selBtn.style.padding = '2px 8px';
      selBtn.style.marginLeft = '8px';
      selBtn.textContent = '📍 Chốt';
      selBtn.onclick = (e) => {
        e.stopPropagation();
        document.querySelectorAll('.folder-item').forEach(i => i.classList.remove('selected'));
        item.classList.add('selected');
        _selectedFolderPath = ent.path;
        $('selectedPathDisplay').textContent = 'Đang chọn: ' + ent.path;
        $('confirmFolderBtn').disabled = false;
      };
      
      item.appendChild(selBtn);
      list.appendChild(item);
    });

  } catch (e) {
    if (request !== _browseRequest) return;
    $('folderList').textContent = '❌ ' + e.message;
    $('selectedPathDisplay').textContent = 'Chưa chọn được thư mục';
  }
}

async function confirmFolder() {
  if (!_selectedFolderPath || !_browserTarget) return;
  
  if (_browserTarget === 'save') {
    $('savePathInput').value = _selectedFolderPath;
    if (!await setSavePath(_selectedFolderPath)) return;
  } else {
    const slot = parseInt(_browserTarget.replace('folder', ''));
    $(`folder${slot}`).value = _selectedFolderPath;
    state.imageFolders[slot] = _selectedFolderPath;
    rememberPickerPath(_selectedFolderPath);
    addLog(`📂 Folder ${slot === 3 ? 'Mặt Trước' : 'Mặt Sau'}: ${_selectedFolderPath}`);
  }
  
  closeBrowser();
}

// ── SAVE PATH ─────────────────────────────────────────────────
async function setSavePath(path) {
  path = path || $('savePathInput').value.trim();
  const badge = $('savePathStatus');
  if (!path) { clearSavePath(); return; }
  showLoading('Đang kiểm tra thư mục...');
  try {
    const res  = await fetch('/api/config/save-path', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path })
    });
    const data = await res.json();
    if (data.success) {
      state.savePath    = data.path;
      $('savePathInput').value = data.path;
      badge.textContent = '✓ Đã chọn';
      badge.className   = 'save-path-badge save-path-ok';
      addLog(`💾 Nơi lưu file: ${path}`);
      return true;
    } else {
      badge.textContent = '✗ Lỗi';
      badge.className   = 'save-path-badge save-path-error';
      addLog('❌ ' + (data.error || 'Lỗi đường dẫn'));
      showNotice('Không chọn được nơi lưu', data.error || 'Lỗi đường dẫn');
    }
  } catch (e) {
    addLog('❌ ' + e.message);
    showNotice('Không chọn được nơi lưu', e.message);
  } finally { hideLoading(); }
  $('savePathInput').value = state.savePath;
  return false;
}

async function clearSavePath() {
  $('savePathInput').value = '';
  $('savePathStatus').textContent = 'Chưa chọn';
  $('savePathStatus').className   = 'save-path-badge save-path-empty';
  state.savePath = '';
  await fetch('/api/config/save-path', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: '' })
  });
  addLog('💾 Đã xóa nơi lưu file');
}

function clearFolder(slot) {
  $(`folder${slot}`).value = '';
  state.imageFolders[slot] = '';
  addLog(`📂 Đã xóa folder slot ${slot}`);
}

// ── IMAGE INPUTS ──────────────────────────────────────────────
function setupImageInputs() {
  const isMobile = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);

  [1,2,3,4].forEach(n => {
    const inp  = $(`img${n}`);
    const gal  = $(`img${n}Gallery`);
    const cam  = $(`img${n}Camera`);
    
    // Xử lý sự kiện chọn file từ input truyền thống (fallback)
    if (inp) inp.addEventListener('change', () => handleImageFile(n, inp.files[0]));
    if (gal) gal.addEventListener('change', () => handleImageFile(n, gal.files[0]));

    // Camera: Luôn dùng browser native input — KHÔNG intercept
    // Thêm xử lý EXIF-aware cho ảnh từ camera
    if (cam) {
      cam.addEventListener('change', async () => {
        const file = cam.files[0];
        if (!file) return;
        // Xử lý EXIF orientation và resize nếu cần
        const correctedFile = await correctImageOrientation(file);
        handleImageFile(n, correctedFile);
        // Tự lưu ảnh chụp lên máy chủ để lần sau dùng lại
        void autoSaveCapture(n, correctedFile);
        // Reset để có thể chụp lại cùng slot
        cam.value = '';
      });
    }

    /** 
     * FORCE NATIVE PICKER: Chỉ áp dụng trên PC (localhost),
     * không áp dụng trên mobile vì sẽ block camera.
     */
    if (gal && !isMobile) {
      gal.addEventListener('click', async (e) => {
        // Máy truy cập từ xa dùng bộ chọn ảnh của chính thiết bị đó.
        if (!_storageConfig ||
            !['localhost', '127.0.0.1'].includes(location.hostname)) return;
        // Nếu click là do code gọi lại (fallback), cho phép chạy tiếp
        if (e.isTrusted === false) return; 

        e.preventDefault();
        e.stopPropagation();
        
        showLoading('Đang mở bộ chọn file...');
        try {
          await smartPickFile(n);
        } finally {
          hideLoading();
        }
      });
    }
  });
}

/**
 * Sửa EXIF orientation của ảnh camera (iOS/Android thường xoay ảnh).
 * Trả về File mới đã được vẽ lại đúng chiều trên Canvas.
 */
 async function decodeImageIgnoringExif(file) {
   // Đọc pixel thô rồi chỉ áp dụng EXIF một lần trong canvas. Điều này tránh
   // lỗi xoay hai lần trên Safari/Chrome vốn đã tự hiển thị EXIF cho <img>.
   if (typeof createImageBitmap === 'function') {
     try {
       return { image: await createImageBitmap(file, { imageOrientation: 'none' }), raw: true };
     } catch (_) { /* fallback */ }
   }
   return await new Promise((resolve, reject) => {
     const img = new Image();
     const url = URL.createObjectURL(file);
     img.onload = () => { URL.revokeObjectURL(url); resolve({ image: img, raw: false }); };
     img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Không đọc được ảnh')); };
     img.src = url;
   });
 }

 async function correctImageOrientation(file) {
   try {
     const decoded = await decodeImageIgnoringExif(file);
     const source = decoded.image;
     const sourceW = source.width || source.naturalWidth;
     const sourceH = source.height || source.naturalHeight;
     if (!sourceW || !sourceH) return file;

     // Fallback <img> thường đã tự áp dụng EXIF; chỉ đọc EXIF thủ công khi
     // createImageBitmap thực sự trả về pixel thô.
     const orientation = decoded.raw ? await getExifOrientation(file) : 1;
     let width = sourceW;
     let height = sourceH;
     const MAX = 2400;
     if (Math.max(width, height) > MAX) {
       const ratio = MAX / Math.max(width, height);
       width = Math.round(width * ratio);
       height = Math.round(height * ratio);
     }

     const swapsAxes = orientation >= 5 && orientation <= 8;
     const canvas = document.createElement('canvas');
     canvas.width = swapsAxes ? height : width;
     canvas.height = swapsAxes ? width : height;
     const ctx = canvas.getContext('2d', { alpha: false });
     if (!ctx) return file;
     ctx.fillStyle = '#fff';
     ctx.fillRect(0, 0, canvas.width, canvas.height);

     // Ma trận chuẩn EXIF 1–8 theo kích thước pixel đã scale.
     switch (orientation) {
       case 2: ctx.setTransform(-1, 0, 0, 1, width, 0); break;
       case 3: ctx.setTransform(-1, 0, 0, -1, width, height); break;
       case 4: ctx.setTransform(1, 0, 0, -1, 0, height); break;
       case 5: ctx.setTransform(0, 1, 1, 0, 0, 0); break;
       case 6: ctx.setTransform(0, 1, -1, 0, height, 0); break;
       case 7: ctx.setTransform(0, -1, -1, 0, height, width); break;
       case 8: ctx.setTransform(0, -1, 1, 0, 0, width); break;
       default: ctx.setTransform(1, 0, 0, 1, 0, 0);
     }
     ctx.drawImage(source, 0, 0, sourceW, sourceH, 0, 0, width, height);
     source.close?.();

     const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92));
     if (!blob) return file;
     return new File([blob], (file.name || 'capture.jpg').replace(/\.[^.]+$/, '.jpg'), {
       type: 'image/jpeg', lastModified: Date.now()
     });
   } catch (_) {
     return file;
   }
 }

/**
 * Đọc EXIF orientation từ JPEG header (không cần thư viện ngoài).
 * Trả về số 1-8 (hoặc 1 nếu không có EXIF).
 */
function getExifOrientation(file) {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const view = new DataView(e.target.result);
        if (view.getUint16(0, false) !== 0xFFD8) { resolve(1); return; }
        let offset = 2;
        while (offset < view.byteLength) {
          const marker = view.getUint16(offset, false);
          offset += 2;
          if (marker === 0xFFE1) {
            if (view.getUint32(offset += 2, false) !== 0x45786966) { resolve(1); return; }
            const little = view.getUint16(offset += 6, false) === 0x4949;
            offset += view.getUint32(offset + 4, little);
            const tags = view.getUint16(offset, little);
            offset += 2;
            for (let i = 0; i < tags; i++) {
              if (view.getUint16(offset + (i * 12), little) === 0x0112) {
                resolve(view.getUint16(offset + (i * 12) + 8, little));
                return;
              }
            }
          } else if ((marker & 0xFF00) !== 0xFF00) break;
          else offset += view.getUint16(offset, false);
        }
      } catch (ex) { /* ignore */ }
      resolve(1);
    };
    reader.onerror = () => resolve(1);
    reader.readAsArrayBuffer(file.slice(0, 64 * 1024));
  });
}

/** 
 * Bộ chọn file thông minh: Mở đúng thư mục cũ và điền tên ảnh tồn tại kế tiếp.
 * Sử dụng NATIVE API để can thiệp được vào ô "File name" của Windows
 */
async function smartPickFile(slot) {
  // Chỉ slot 1 và 2 dùng chung thư mục và tên file gợi ý.
  const sharesPickerMemory = slot === 1 || slot === 2;
  const initDir = sharesPickerMemory ? state.lastImageFolder12 : '';
  const suggested = sharesPickerMemory
    ? await nextExistingImageName(initDir, state.lastImageName12)
    : '';

  try {
    // Gọi API Native của Java để mở hộp thoại Windows chuẩn
    const url = `/api/native-pick-file?initial=${encodeURIComponent(initDir)}&suggested=${encodeURIComponent(suggested)}`;
    
    // Đặt timeout 60s cho việc chọn file
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 60000);
    
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timeoutId);
    
    if (!res.ok) throw new Error('Server không phản hồi');
    const data = await res.json();

    if (data.success && data.path) {
      const fullPath = data.path;
      
      const lastSlash = fullPath.lastIndexOf('\\');
      if (sharesPickerMemory) {
        const selectedDir = lastSlash > 0 ? fullPath.substring(0, lastSlash) : '';
        if (selectedDir) {
          state.lastImageFolder12 = selectedDir;
        }
      }

      // Fetch file từ đường dẫn local qua proxy API của Java
      const fileRes = await fetch(`/api/image?path=${encodeURIComponent(fullPath)}`);
      if (!fileRes.ok) throw new Error('Không thể đọc file từ đường dẫn: ' + fullPath);
      
      const blob = await fileRes.blob();
      const fileName = fullPath.substring(lastSlash + 1);
      const file = new File([blob], fileName, { type: blob.type || 'image/jpeg' });
      
      handleImageFile(slot, file);
    }
  } catch (err) {
    console.error('Lỗi Native Picker:', err);
    // Fallback: Nếu native lỗi, dùng bộ chọn mặc định bằng cách tạo sự kiện click ảo
    const galInput = $(`img${slot}Gallery`);
    if (galInput) {
       const event = new MouseEvent('click', { view: window, bubbles: true, cancelable: true });
       // Đánh dấu đây là event nhân tạo để tránh loop
       Object.defineProperty(event, 'isTrusted', { value: false }); 
       galInput.dispatchEvent(event);
    }
  }
}

/**
 * Lấy đúng tên file ảnh tồn tại kế tiếp trong folder đang mở.
 * Không tự cộng số, vì ảnh có thể đã bị xóa hoặc thứ tự chụp có thể bị đứt quãng.
 */
async function nextExistingImageName(folder, afterName) {
  if (!folder || !afterName) return '';
  try {
    const url = `/api/next-image?folder=${encodeURIComponent(folder)}&after=${encodeURIComponent(afterName)}`;
    const response = await fetch(url);
    const data = await response.json();
    return response.ok && data.success ? (data.name || '') : '';
  } catch (_) {
    return '';
  }
}

/** Hiển thị badge gợi ý tên file cho slot */
function showFilenameSuggestion(slot, suggested) {
  // Xóa badge cũ trước
  document.querySelectorAll('.filename-hint-badge').forEach(el => el.remove());
  if (!suggested) return;

  const slotEl = $(`slot${slot}`);
  if (!slotEl) return;

  const badge = document.createElement('div');
  badge.className = 'filename-hint-badge';
  badge.innerHTML = `📄 Gợi ý: <strong>${suggested}</strong>`;
  badge.title = 'Tên file được gợi ý tự động dựa trên ảnh trước';

  // Thêm badge vào header của slot
  const header = slotEl.querySelector('.img-slot-header');
  if (header) header.appendChild(badge);

  // Tự ẩn sau 8 giây
  setTimeout(() => badge.remove(), 8000);
}

function handleImageFile(slot, file) {
  if (!file) return;
  state.images[slot]    = file;
  state.rotations[slot] = 0;

  // Slot 1 và 2 chia sẻ vị trí/tên để mở lại đúng folder cho ảnh kế tiếp.
  state.lastImageName = file.name;
  if (slot === 1 || slot === 2) {
    state.lastImageName12 = file.name;
    void showNextExistingImageSuggestion(slot);
  }

  const reader = new FileReader();
  reader.onload = e => {
    renderPreview(slot, e.target.result);
    // Chỉ quét khi người dùng bấm Kiểm tra & tự động xuất file.
    if (slot === 2) {
      _ocrResult = null;
      setBadge2('ready', 'Sẵn sàng quét khi xuất file');
    }
  };
  reader.readAsDataURL(file);
  addLog(`📷 Ảnh ${slot}: ${file.name}`);
  updateProgress();
}

/**
 * Tự lưu ảnh vừa chụp lên máy chạy app (thư mục capturesRoot) để lần sau
 * dùng lại được. Chạy ngầm, không chặn thao tác của người dùng.
 */
async function autoSaveCapture(slot, file) {
  if (!state.capturesRoot) return;
  try {
    const form = new FormData();
    form.append('image', file, 'capture.jpg');
    form.append('slot', String(slot));
    const serial = ($('serialManual')?.value || state.selectedSN || '').trim();
    if (serial) form.append('serialNumber', serial);
    const res = await fetch('/api/capture/save', { method: 'POST', body: form });
    const data = await res.json();
    if (data.saved) {
      addLog(`💾 Đã lưu vào máy chủ: ${data.filename}`);
    } else if (!res.ok) {
      addLog('⚠ Không lưu được ảnh vào máy chủ: ' + (data.error || 'Lỗi không rõ'));
    }
  } catch (e) {
    addLog('⚠ Không lưu được ảnh vào máy chủ: ' + e.message);
  }
}

async function showNextExistingImageSuggestion(slot) {
  const nextSlot = slot === 1 ? 2 : (slot === 2 ? 1 : null);
  if (!nextSlot || state.images[nextSlot]) return;
  const nextName = await nextExistingImageName(state.lastImageFolder12, state.lastImageName12);
  if (nextName) showFilenameSuggestion(nextSlot, nextName);
}

function renderPreview(slot, src) {
  const preview = $(`preview${slot}`);
  const deg = state.rotations[slot] || 0;
  const labels = { 1:'Ảnh Toàn Cảnh', 2:'Ảnh Zoom Serial', 3:'Ảnh Mặt Trước', 4:'Ảnh Mặt Sau' };
  preview.innerHTML = `<img src="${src}" alt="Ảnh ${slot}"
    style="width:100%;height:100%;object-fit:contain;transform:${getRotateTransform(deg)};transition:transform 0.4s ease;transform-origin:center"
    id="imgEl${slot}"
    onload="fitMobilePreview(${slot}, this)"
    onclick="openLightboxFromSrc('${src}', '${labels[slot]}')"
    title="Nhấn để xem lớn"/>`;
  $(`slot${slot}`).classList.add('has-image');
}

function fitMobilePreview(slot, img) {
  const preview = $(`preview${slot}`);
  if (!preview || !img || !window.matchMedia('(max-width: 768px)').matches) return;
  // Ảnh chụp thường là ảnh dọc; cho khung cao theo đúng tỉ lệ để không cắt ảnh.
  const ratio = img.naturalWidth > 0 ? img.naturalHeight / img.naturalWidth : 1;
  const width = preview.clientWidth || img.clientWidth || 1;
  preview.style.aspectRatio = 'auto';
  preview.style.height = `${Math.max(220, Math.round(width * ratio))}px`;
  img.style.width = '100%';
  img.style.height = '100%';
  img.style.objectFit = 'contain';
}

// ── LIGHTBOX FLOATING WINDOW ──────────────────────────────────
let _lbZoom  = 1;
let _lbPanX  = 0;
let _lbPanY  = 0;
let _lbRot   = 0;
let _lbDrag  = false;   // dragging image (pan)
let _lbWDrag = false;   // dragging window
let _lbLast  = { x:0, y:0 };
let _lbWOrig = { x:0, y:0, l:0, t:0 }; // window drag origin

function openLightboxFromSrc(src, label) {
  if (!src) return;
  const img = $('lightboxImg');
  img.src = src;
  _lbZoom = 1; _lbPanX = 0; _lbPanY = 0; _lbRot = 0;

  $('lbTitle').textContent = `${label || 'Ảnh'} — Lăn chuột: Zoom / Kéo: Pan`;
  $('lightbox').classList.remove('hidden');

  // Center image once loaded
  img.onload = () => lbFitScreen();
  if (img.complete) lbFitScreen();

  initLightboxEvents();
}

function closeLightbox() {
  $('lightbox').classList.add('hidden');
  _lbDrag = _lbWDrag = false;
}

function lbReset()  { _lbZoom=1; _lbPanX=0; _lbPanY=0; _lbRot=0; lbApply(); }

function lbFitScreen() {
  const canvas = $('lbCanvas');
  const img    = $('lightboxImg');
  if (!img.naturalWidth) return;
  const scaleX = canvas.clientWidth  / img.naturalWidth;
  const scaleY = canvas.clientHeight / img.naturalHeight;
  _lbZoom = Math.min(scaleX, scaleY) * 0.95;
  // Center
  _lbPanX = (canvas.clientWidth  - img.naturalWidth  * _lbZoom) / 2;
  _lbPanY = (canvas.clientHeight - img.naturalHeight * _lbZoom) / 2;
  _lbRot  = 0;
  lbApply();
}

function lbZoomStep(delta) {
  const canvas = $('lbCanvas');
  const cx = canvas.clientWidth / 2, cy = canvas.clientHeight / 2;
  lbZoomAt(cx, cy, 1 + delta);
}

function lbZoomAt(cx, cy, factor) {
  const newZoom = Math.max(0.1, Math.min(20, _lbZoom * factor));
  const scale   = newZoom / _lbZoom;
  _lbPanX = cx - (cx - _lbPanX) * scale;
  _lbPanY = cy - (cy - _lbPanY) * scale;
  _lbZoom = newZoom;
  lbApply();
}

function lightboxRotate() {
  _lbRot = (_lbRot + 90) % 360;
  lbApply();
}

function lbApply() {
  const img = $('lightboxImg');
  img.style.transform = `translate(${_lbPanX}px, ${_lbPanY}px) scale(${_lbZoom}) rotate(${_lbRot}deg)`;
  $('lbZoomPct').textContent = Math.round(_lbZoom * 100) + '%';
}

function lbMinimize() {
  const win = $('lightboxWin');
  win.style.height = win.style.height === '38px' ? '' : '38px';
}

let _lbEventsInit = false;
function initLightboxEvents() {
  if (_lbEventsInit) return;
  _lbEventsInit = true;
  const canvas   = $('lbCanvas');
  const titlebar = $('lbTitlebar');
  const win      = $('lightboxWin');

  // Mouse-wheel zoom on canvas
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect   = canvas.getBoundingClientRect();
    const cx     = e.clientX - rect.left;
    const cy     = e.clientY - rect.top;
    const factor = e.deltaY < 0 ? 1.12 : 1/1.12;
    lbZoomAt(cx, cy, factor);
  }, { passive: false });

  // Pan: mouse drag on canvas
  canvas.addEventListener('mousedown', e => {
    e.preventDefault();
    _lbDrag = true;
    _lbLast = { x: e.clientX, y: e.clientY };
    canvas.classList.add('grabbing');
  });

  // Window drag: mouse on titlebar
  titlebar.addEventListener('mousedown', e => {
    if (e.target.classList.contains('lb-dot')) return;
    e.preventDefault();
    _lbWDrag = true;
    const rect = win.getBoundingClientRect();
    _lbWOrig  = { x: e.clientX, y: e.clientY, l: rect.left, t: rect.top };
  });

  document.addEventListener('mousemove', e => {
    if (_lbDrag) {
      _lbPanX += e.clientX - _lbLast.x;
      _lbPanY += e.clientY - _lbLast.y;
      _lbLast  = { x: e.clientX, y: e.clientY };
      lbApply();
    }
    if (_lbWDrag) {
      win.style.left = (_lbWOrig.l + e.clientX - _lbWOrig.x) + 'px';
      win.style.top  = (_lbWOrig.t + e.clientY - _lbWOrig.y) + 'px';
    }
  });

  document.addEventListener('mouseup', () => {
    _lbDrag = _lbWDrag = false;
    canvas.classList.remove('grabbing');
  });

  // Touch: pinch-to-zoom & one-finger pan
  let _tLast = null, _tDist = 0;
  canvas.addEventListener('touchstart', e => {
    if (e.touches.length === 1) {
      _lbDrag = true;
      _lbLast = { x: e.touches[0].clientX, y: e.touches[0].clientY };
    } else if (e.touches.length === 2) {
      _lbDrag = false;
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dy = e.touches[0].clientY - e.touches[1].clientY;
      _tDist = Math.hypot(dx, dy);
      _tLast = { x:(e.touches[0].clientX+e.touches[1].clientX)/2,
                 y:(e.touches[0].clientY+e.touches[1].clientY)/2 };
    }
    e.preventDefault();
  }, { passive: false });

  canvas.addEventListener('touchmove', e => {
    e.preventDefault();
    if (e.touches.length === 1 && _lbDrag) {
      _lbPanX += e.touches[0].clientX - _lbLast.x;
      _lbPanY += e.touches[0].clientY - _lbLast.y;
      _lbLast  = { x: e.touches[0].clientX, y: e.touches[0].clientY };
      lbApply();
    } else if (e.touches.length === 2) {
      const dx   = e.touches[0].clientX - e.touches[1].clientX;
      const dy   = e.touches[0].clientY - e.touches[1].clientY;
      const dist = Math.hypot(dx, dy);
      const midX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
      const midY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      const rect = canvas.getBoundingClientRect();
      lbZoomAt(midX - rect.left, midY - rect.top, dist / _tDist);
      _tDist = dist;
      if (_tLast) {
        _lbPanX += midX - _tLast.x; _lbPanY += midY - _tLast.y;
        _tLast = { x: midX, y: midY }; lbApply();
      }
    }
  }, { passive: false });

  canvas.addEventListener('touchend', () => { _lbDrag = false; });
}

// ESC to close
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') { closeLightbox(); closeSnModal(); closeBrowser(); closeFuzzyModal(); }
  if (e.key === '+' && !$('lightbox').classList.contains('hidden')) lbZoomStep(0.2);
  if (e.key === '-' && !$('lightbox').classList.contains('hidden')) lbZoomStep(-0.2);
});

// ── ROTATE IMAGE ──────────────────────────────────────────────
function rotateImage(slot) {
  if (!state.images[slot]) return;
  state.rotations[slot] = ((state.rotations[slot] || 0) + 90) % 360;
  const img = $(`imgEl${slot}`);
  if (img) img.style.transform = getRotateTransform(state.rotations[slot]);
  rebuildRotatedBlob(slot);
}

function getRotateTransform(deg) {
  const scale = (deg === 90 || deg === 270) ? 0.75 : 1;
  return `rotate(${deg}deg) scale(${scale})`;
}

function rebuildRotatedBlob(slot) {
  const file = state.images[slot];
  if (!file) return;
  const deg = state.rotations[slot];
  const reader = new FileReader();
  reader.onload = e => {
    const image = new Image();
    image.onload = () => {
      const canvas  = document.createElement('canvas');
      const isOdd   = deg === 90 || deg === 270;
      canvas.width  = isOdd ? image.height : image.width;
      canvas.height = isOdd ? image.width  : image.height;
      const ctx = canvas.getContext('2d');
      ctx.translate(canvas.width / 2, canvas.height / 2);
      ctx.rotate((deg * Math.PI) / 180);
      ctx.drawImage(image, -image.width / 2, -image.height / 2);
      canvas.toBlob(blob => {
        state.images[slot] = new File([blob], file.name, { type: 'image/jpeg' });
      }, 'image/jpeg', 0.92);
    };
    image.src = e.target.result;
  };
  reader.readAsDataURL(file);
}

function clearImage(slot) {
  state.images[slot]    = null;
  state.rotations[slot] = 0;
  $(`preview${slot}`).innerHTML = '<span class="placeholder-text">[Chưa chọn ảnh]</span>';
  $(`slot${slot}`).classList.remove('has-image');
  $(`img${slot}`).value        = '';
  $(`img${slot}Gallery`).value = '';
  const camInp = $(`img${slot}Camera`);
  if (camInp) camInp.value = '';
  updateProgress();
}

// ── RANDOM ẢNH TỪ FOLDER ─────────────────────────────────────
let _randomBusy = false;

async function randomImages() {
  if (_randomBusy) return;
  const f3 = state.imageFolders[3];
  const f4 = state.imageFolders[4];
  if (!f3 && !f4) {
    addLog('⚠ Chưa chọn folder ảnh cho Mặt Trước hoặc Mặt Sau');
    setStatusMsg('⚠ Cần chọn folder ảnh ở slot 3 hoặc 4', 'warn');
    return;
  }
  _randomBusy = true;
  const button = document.querySelector('.btn-random');
  if (button) button.disabled = true;
  showLoading('Đang chọn ảnh ngẫu nhiên...');
  try {
    const tasks = [];
    if (f3) tasks.push(loadRandomImage(3, f3));
    if (f4) tasks.push(loadRandomImage(4, f4));
    const results = await Promise.all(tasks);
    const loaded = results.filter(Boolean).length;
    if (loaded === tasks.length) {
      setStatusMsg('✅ Random ảnh thành công!', 'ok');
    } else if (loaded > 0) {
      setStatusMsg(`⚠ Đã random ${loaded}/${tasks.length} ảnh`, 'warn');
    } else {
      setStatusMsg('❌ Không random được ảnh. Kiểm tra lại folder ảnh.', 'err');
    }
  } finally {
    _randomBusy = false;
    if (button) button.disabled = false;
    hideLoading();
  }
}

async function loadRandomImage(slot, folder) {
  try {
    const res  = await fetch('/api/random-image?path=' + encodeURIComponent(folder));
    const data = await res.json();
    if (!res.ok || !data.success) { addLog(`❌ Slot ${slot}: ${data.error || 'Không tìm được ảnh'}`); return false; }

    // Fetch ảnh về dưới dạng blob qua /api/image proxy
    const imgRes = await fetch('/api/image?path=' + encodeURIComponent(data.path));
    if (!imgRes.ok) { addLog(`❌ Slot ${slot}: Không đọc được ảnh`); return false; }

    const blob = await imgRes.blob();
    const file = new File([blob], data.name, { type: blob.type || 'image/jpeg' });
    state.images[slot]    = file;
    state.rotations[slot] = 0;
    const src = URL.createObjectURL(blob);
    renderPreview(slot, src);
    addLog(`🎲 Slot ${slot}: ${data.name}`);
    updateProgress();
    return true;
  } catch (e) {
    addLog(`❌ Slot ${slot}: ${e.message}`);
    return false;
  }
}

// ── PROGRESS BAR ─────────────────────────────────────────────
function updateProgress() {
  let score = 0;
  if (state.excelLoaded)               score += 10;
  if ($('deviceName').value.trim())    score += 20;
  if ($('productCode').value.trim())   score += 15;
  if ($('poNumber').value.trim())      score += 15;
  if ($('serialManual').value.trim())  score += 10;
  const imgCount = Object.values(state.images).filter(Boolean).length;
  score += imgCount * 7;   // max 28

  $('progressBar').style.width  = Math.min(score, 100) + '%';
  $('imgCounter').textContent   = `${imgCount} / 4`;
  $('statusMsg').textContent    = `Tiến trình: ${Math.min(score, 100)}%`;
}

// ── EXPORT REPORT ─────────────────────────────────────────────
let _exportBusy = false;
async function exportReport() {
  if (_exportBusy) return;
  _exportBusy = true;
  $('exportBtn').disabled = true;
  try { await checkAndExportReport(); }
  catch (error) { addLog('❌ ' + error.message); setStatusMsg('❌ ' + error.message, 'err'); }
  finally { _exportBusy = false; $('exportBtn').disabled = false; hideLoading(); }
}

function rankSerialSuggestions(candidates, serials) {
  return serials.map(best => {
    let score = 0;
    for (const candidate of candidates) {
      // Đối chiếu cả SN gốc và các nhầm lẫn OCR thường gặp, chỉ để gợi ý.
      for (const text of [candidate, candidate.replace(/O/g, '0').replace(/I/g, '1').replace(/B/g, '8')]) {
        score = Math.max(score, Math.round(levenshteinRatio(text, best) * 100));
      }
    }
    return { best, score };
  }).filter(item => item.score >= 80).sort((a, b) => b.score - a.score).slice(0, 5);
}

async function checkAndExportReport() {
  state.pendingExport = null;
  const deviceName   = $('deviceName').value.trim();
  const productCode  = $('productCode').value.trim();
  const poNumber     = $('poNumber').value.trim();
  const reportType   = document.querySelector('input[name=reportType]:checked')?.value;
  const repairNotes  = $('repairNotes').value.trim();
  const serialManualRaw = $('serialManual').value.trim();
  const serialManual = serialManualRaw ? serialManualRaw.toUpperCase().replace(/[^A-Z0-9.]/g, '') : '';

  // Validate bắt buộc
  const errors = [];
  if (!deviceName)  errors.push('Tên Thiết Bị');
  if (!productCode) errors.push('Mã Hàng Hóa');
  if (!poNumber)    errors.push('Số PO');
  if (!reportType)  errors.push('Loại báo cáo');
  if (errors.length) {
    addLog('⚠ Thiếu: ' + errors.join(', '));
    setStatusMsg('⚠ Vui lòng điền đầy đủ thông tin bắt buộc!', 'warn');
    return;
  }

  const imgCount = Object.values(state.images).filter(Boolean).length;
  if (imgCount < 4) {
    if (!confirm(`Bạn chỉ có ${imgCount}/4 ảnh. Vẫn tiếp tục xuất file?`)) return;
  }

  // Nếu không nhập Serial thủ công → kiểm tra OCR
  if (!serialManual) {
    if (!state.images[2]) throw new Error('Vui lòng chọn ảnh Zoom (Serial) để quét.');
    showLoading('Đang quét SN và đối chiếu Excel...');
    if (state.excelLoaded) await loadExcelSNList();
    await triggerOCR(state.images[2]);
    hideLoading();
    if (state.excelLoaded) {
      // Thử tìm auto-match 100% hoặc lấy danh sách fuzzy
      let exactMatch = null;
      let topCandidate = null;
      let fuzzyMatches = []; // List of { cand, best, score }

      if (_ocrResult && _ocrResult.candidates && _ocrResult.candidates.length > 0) {
        topCandidate = _ocrResult.candidates[0]; // best raw candidate
        for (const cand of _ocrResult.candidates) {
          if (allSNList.includes(cand)) { exactMatch = cand; break; }
        }
      }

      if (exactMatch) {
        // Tự động xuất file không cần mở bảng
        state.pendingExport = { deviceName, productCode, poNumber, reportType, repairNotes };
        await doExport(exactMatch, { verifiedFromExcel: true });
        return;
      }

      // Lưu pending export data
      state.pendingExport = { deviceName, productCode, poNumber, reportType, repairNotes };
      fuzzyMatches = rankSerialSuggestions(_ocrResult?.candidates || [], allSNList);
      openFuzzyModal(topCandidate || '', fuzzyMatches);
      return;
    } else {
      state.pendingExport = { deviceName, productCode, poNumber, reportType, repairNotes };
      openFuzzyModal(_ocrResult?.candidates?.[0] || '', []);
      return;
    }
  }

  await doExport(serialManual);
}

async function doExport(serialNumber, options = {}) {
  const { deviceName, productCode, poNumber, reportType, repairNotes } =
    state.pendingExport || {
      deviceName:  $('deviceName').value.trim(),
      productCode: $('productCode').value.trim(),
      poNumber:    $('poNumber').value.trim(),
      reportType:  document.querySelector('input[name=reportType]:checked')?.value,
      repairNotes: $('repairNotes').value.trim(),
    };

  // Kiểm tra SN với Excel
  if (serialNumber && state.excelLoaded && options.verifiedFromExcel) {
    const box = $('snCheckResult');
    box.classList.remove('hidden', 'sn-notfound');
    box.className = 'sn-check-result sn-found';
    box.textContent = `✅ SN "${serialNumber}" TÌM THẤY trong Excel`;
    addLog(`✅ SN khớp Excel (đã xác nhận từ OCR): ${serialNumber}`);
  }
  if (serialNumber && state.excelLoaded && !options.verifiedFromExcel) {
    addLog(`🔍 Kiểm tra SN: ${serialNumber}...`);
    try {
      const snRes  = await fetch('/api/excel/check-sn', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sn: serialNumber })
      });
      const snData = await snRes.json();
      const box = $('snCheckResult');
      box.classList.remove('hidden', 'sn-found', 'sn-notfound');
      if (snData.found) {
        box.className   = 'sn-check-result sn-found';
        box.textContent = `✅ SN "${serialNumber}" TÌM THẤY trong Excel`;
        addLog(`✅ SN khớp: ${serialNumber}`);
      } else {
        box.className   = 'sn-check-result sn-notfound';
        box.textContent = `❌ SN "${serialNumber}" KHÔNG TÌM THẤY trong Excel`;
        addLog(`❌ SN không khớp: ${serialNumber}`);
        if (!confirm('SN không tìm thấy trong Excel. Vẫn tiếp tục xuất file?')) return;
      }
    } catch (e) { addLog('⚠ Không kiểm tra được SN: ' + e.message); }
  }

  showLoading('Đang nén ảnh...');
  addLog('🖼 Đang nén ảnh trước khi tạo file Word...');

  // ── Nén tất cả ảnh trước khi upload (để giảm thời gian tạo Word) ──
  const compressedImages = {};
  await Promise.all([1, 2, 3, 4].map(async n => {
    if (!state.images[n]) return;
    try {
      compressedImages[n] = await compressImageForWord(state.images[n]);
      const origKB = Math.round(state.images[n].size / 1024);
      const compKB = Math.round(compressedImages[n].size / 1024);
      addLog(`🖼 Ảnh ${n}: ${origKB}KB → ${compKB}KB`);
    } catch (e) {
      // fallback: dùng ảnh gốc nếu nén lỗi
      compressedImages[n] = state.images[n];
    }
  }));

  showLoading('Đang tạo file Word...');
  addLog('📄 Đang tạo báo cáo...');

  const form = new FormData();
  form.append('deviceName',   deviceName);
  form.append('productCode',  productCode);
  form.append('poNumber',     poNumber);
  form.append('reportType',   reportType);
  form.append('repairNotes',  repairNotes);
  form.append('serialNumber', serialNumber || '');
  [1,2,3,4].forEach(n => { if (compressedImages[n]) form.append(`image${n}`, compressedImages[n], `image${n}.jpg`); });

  try {
    const res = await fetch('/api/report/generate', { method: 'POST', body: form });
    const contentType = res.headers.get('Content-Type') || '';

    // ── Nếu backend trả JSON → đã lưu server-side (có savePath) ──
    if (contentType.includes('application/json')) {
      const data = await res.json();
      if (res.status === 409 && data.code === 'FILE_EXISTS') {
        showNotice('File đã tồn tại, vui lòng kiểm tra lại', data.savedPath || data.filename);
        addLog('⚠ File đã tồn tại: ' + data.filename);
        setStatusMsg('⚠ File đã tồn tại, vui lòng kiểm tra lại', 'err');
        return; // Giữ nguyên ảnh và dữ liệu để người dùng kiểm tra.
      }
      if (!res.ok || !data.success) {
        throw new Error(data.error || 'Server lỗi ' + res.status);
      }
      // Hiển thị thông báo lưu thành công (không download)
      addLog(`💾 Đã lưu: ${data.savedPath}`);
      setStatusMsg(`✅ Lưu thành công: ${data.filename}`, 'ok');
      showSavedToast(data.filename, data.savedPath);
    } else {
      // ── Trả blob → download (chưa cấu hình savePath) ──
      if (!res.ok) throw new Error('Server lỗi ' + res.status);
      const blob     = await res.blob();
      const filename = getFilename(res) || `BaoCao_${reportType}_${serialNumber || 'noSN'}.docx`;
      downloadBlob(blob, filename);
      addLog(`✅ Xuất file: ${filename}`);
      setStatusMsg('✅ Xuất file thành công!', 'ok');
    }

    $('progressBar').style.width = '100%';
    state.pendingExport = null;
  } catch (e) {
    addLog('❌ Lỗi: ' + e.message);
    setStatusMsg('❌ Lỗi xuất file!', 'err');
    showNotice('Không xuất được file Word', e.message);
  } finally { hideLoading(); }
}

function showNotice(message, detail = '') {
  $('noticeTitle').textContent = message;
  $('noticeDetail').textContent = detail;
  if (!$('noticeDialog').open) $('noticeDialog').showModal();
  $('noticeClose').focus();
}

/** Hiển thị toast thông báo lưu file thành công */
function showSavedToast(filename, savedPath) {
  // Xóa toast cũ nếu còn
  const old = document.getElementById('saved-toast');
  if (old) old.remove();

  const toast = document.createElement('div');
  toast.id = 'saved-toast';
  toast.style.cssText = `
    position:fixed; bottom:28px; right:24px; z-index:9999;
    background:linear-gradient(135deg,#1a2a1a,#0d3d1a);
    border:1px solid #2ecc71; border-radius:12px;
    padding:14px 20px; min-width:300px; max-width:480px;
    box-shadow:0 8px 32px rgba(0,0,0,.5);
    display:flex; align-items:flex-start; gap:12px;
    animation: slideInRight .3s ease;
  `;
  toast.innerHTML = `
    <span style="font-size:1.6rem;line-height:1">💾</span>
    <div style="flex:1;min-width:0">
      <div style="color:#2ecc71;font-weight:700;font-size:.95rem;margin-bottom:4px">
        Đã lưu thành công!
      </div>
      <div style="color:#e0ffe0;font-size:.82rem;word-break:break-all;opacity:.9">
        <span data-saved-name></span>
      </div>
      <div style="color:#a0c0a0;font-size:.75rem;margin-top:4px;word-break:break-all;opacity:.7">
        <span data-saved-path></span>
      </div>
    </div>
    <button onclick="this.closest('#saved-toast').remove()"
      style="background:none;border:none;color:#2ecc71;font-size:1.2rem;cursor:pointer;padding:0;line-height:1;opacity:.7">✕</button>
  `;
  toast.querySelector('[data-saved-name]').textContent = filename;
  toast.querySelector('[data-saved-path]').textContent = savedPath;
  document.body.appendChild(toast);
  // Tự ẩn sau 5 giây
  setTimeout(() => { if (toast.parentNode) toast.remove(); }, 5000);
}




// ── SN COMPARISON MODAL + OCR ─────────────────────────────────
let allSNList = [];
let _snListPromise = null;
let _ocrResult = null;   // { rawText, candidates: [{text, bestMatch, score}] }

/**
 * Lấy danh sách SN dùng chung cho cả desktop và mobile.
 * Một promise dùng chung giúp tránh hai request /api/excel/list-sn nối tiếp
 * khi người dùng vừa tải Excel vừa bấm xuất file ngay lập tức.
 */
function loadExcelSNList() {
  if (allSNList.length) return Promise.resolve(allSNList);
  if (!_snListPromise) {
    _snListPromise = fetch('/api/excel/list-sn')
      .then(response => {
        if (!response.ok) throw new Error('Không tải được danh sách SN Excel.');
        return response.json();
      })
      .then(data => {
        allSNList = Array.isArray(data.sns) ? data.sns : [];
        return allSNList;
      })
      .catch(error => {
        _snListPromise = null;
        throw error;
      });
  }
  return _snListPromise;
}

/**
 * Nén ảnh trước khi gửi /api/report/generate.
 * WordService vẫn dùng chung ở server; bước này chỉ giảm dữ liệu upload từ
 * điện thoại và tránh để trình duyệt phải gửi ảnh camera nhiều megapixel.
 */
function compressImageForWord(file, maxDimension = 1400, quality = 0.82) {
  if (!file) return Promise.resolve(file);
  // Ảnh JPEG nhỏ đã đủ nhẹ; bỏ qua canvas để tiết kiệm CPU trên điện thoại.
  if (file.type === 'image/jpeg' && file.size && file.size <= 1200 * 1024) {
    return Promise.resolve(file);
  }
  return new Promise(resolve => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    const finish = (result) => {
      URL.revokeObjectURL(url);
      resolve(result || file);
    };
    img.onload = () => {
      const sourceW = img.naturalWidth || img.width;
      const sourceH = img.naturalHeight || img.height;
      if (!sourceW || !sourceH) { finish(file); return; }
      const scale = Math.min(1, maxDimension / Math.max(sourceW, sourceH));
      const width = Math.max(1, Math.round(sourceW * scale));
      const height = Math.max(1, Math.round(sourceH * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d', { alpha: false });
      if (!ctx) { finish(file); return; }
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'medium';
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      canvas.toBlob(blob => {
        if (!blob) { finish(file); return; }
        const base = (file.name || 'image').replace(/\.[^.]+$/, '') || 'image';
        finish(new File([blob], `${base}.jpg`, {
          type: 'image/jpeg', lastModified: Date.now()
        }));
      }, 'image/jpeg', quality);
    };
    img.onerror = () => finish(file);
    img.src = url;
  });
}

async function prepareOcrImage(file) {
  if (!file || !file.size || file.size <= 1500 * 1024) return file;
  try {
    const compact = await compressImageForWord(file, 1600, 0.86);
    if (compact && compact.size < file.size) {
      addLog(`📡 Ảnh OCR: ${Math.round(file.size / 1024)}KB → ${Math.round(compact.size / 1024)}KB`);
      return compact;
    }
  } catch (_) { /* giữ ảnh gốc nếu trình duyệt không nén được */ }
  return file;
}

// ── AUTO-CROP NHÃN/BARCODE (Variance-Based) ──────────────────
/**
 * Phát hiện vùng nhãn/barcode dựa trên ĐỘ TƯƠNG PHẢN CAO (variance).
 * Hoạt động với cả nhãn nền tối và nền sáng (không phụ thuộc độ sáng).
 *
 * Barcode/text = nhiều cạnh sáng/tối xen nhau → VARIANCE cao.
 * Nền trơn = màu đồng nhất → variance thấp.
 */
async function cropLabelRegion(file) {
  return new Promise((resolve) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const origW = img.naturalWidth;
      const origH = img.naturalHeight;

      // Thumbnail 300x300 để giữ chi tiết barcode
      const THUMB = 300;
      const scaleX = origW / THUMB;
      const scaleY = origH / THUMB;
      const thumb = document.createElement('canvas');
      thumb.width = THUMB; thumb.height = THUMB;
      const tc = thumb.getContext('2d');
      tc.drawImage(img, 0, 0, THUMB, THUMB);
      const { data: px } = tc.getImageData(0, 0, THUMB, THUMB);

      // Chuyển sang grayscale trước
      const grayArr = new Float32Array(THUMB * THUMB);
      for (let i = 0; i < THUMB * THUMB; i++) {
        const o = i * 4;
        grayArr[i] = 0.299 * px[o] + 0.587 * px[o+1] + 0.114 * px[o+2];
      }

      // Grid 24x24, tính VARIANCE mỗi ô (không phải luminance trung bình)
      const GRID = 24;
      const cellW = THUMB / GRID;
      const cellH = THUMB / GRID;
      const varArr = [];

      for (let gy = 0; gy < GRID; gy++) {
        for (let gx = 0; gx < GRID; gx++) {
          const x0 = Math.round(gx * cellW), x1 = Math.round((gx+1) * cellW);
          const y0 = Math.round(gy * cellH), y1 = Math.round((gy+1) * cellH);
          let sum = 0, sum2 = 0, n = 0;
          for (let y = y0; y < y1; y++) {
            for (let x = x0; x < x1; x++) {
              const v = grayArr[y * THUMB + x];
              sum += v; sum2 += v * v; n++;
            }
          }
          const m = sum / n;
          varArr.push(n > 1 ? (sum2 / n - m * m) : 0);
        }
      }

      // Ngưỡng: top 30% variance cao nhất = vùng có text/barcode
      const sorted = [...varArr].sort((a, b) => b - a);
      const threshold = sorted[Math.floor(sorted.length * 0.30)];

      // Gom tất cả ô có variance > ngưỡng thành bounding box
      let minGX = GRID, maxGX = -1, minGY = GRID, maxGY = -1;
      let highVarCount = 0;
      for (let gy = 0; gy < GRID; gy++) {
        for (let gx = 0; gx < GRID; gx++) {
          if (varArr[gy * GRID + gx] >= threshold) {
            if (gx < minGX) minGX = gx;
            if (gx > maxGX) maxGX = gx;
            if (gy < minGY) minGY = gy;
            if (gy > maxGY) maxGY = gy;
            highVarCount++;
          }
        }
      }

      // Nếu không tìm thấy vùng hợp lệ → fallback crop nửa dưới ảnh (thường là nơi dán nhãn)
      if (minGX > maxGX || minGY > maxGY || highVarCount < 6) {
        addLog('⚠ Variance crop thất bại, dùng crop nửa dưới ảnh...');
        const halfY = Math.round(origH * 0.35);
        const out2 = document.createElement('canvas');
        out2.width = origW; out2.height = origH - halfY;
        out2.getContext('2d').drawImage(img, 0, halfY, origW, origH - halfY, 0, 0, origW, origH - halfY);
        out2.toBlob(blob => resolve(blob), 'image/png');
        return;
      }

      // Mở rộng bounding box thêm 8% mỗi chiều (padding)
      const padGX = Math.max(1, Math.round(GRID * 0.08));
      const padGY = Math.max(1, Math.round(GRID * 0.08));
      minGX = Math.max(0, minGX - padGX);
      maxGX = Math.min(GRID - 1, maxGX + padGX);
      minGY = Math.max(0, minGY - padGY);
      maxGY = Math.min(GRID - 1, maxGY + padGY);

      // Map về tọa độ ảnh gốc
      const cropX = Math.round(minGX * cellW * scaleX);
      const cropY = Math.round(minGY * cellH * scaleY);
      const cropW = Math.min(origW - cropX, Math.round((maxGX - minGX + 1) * cellW * scaleX));
      const cropH = Math.min(origH - cropY, Math.round((maxGY - minGY + 1) * cellH * scaleY));

      // Kiểm tra vùng crop hợp lệ (phải ít nhất 4% diện tích gốc, tối đa 95%)
      const areaRatio = (cropW * cropH) / (origW * origH);
      if (areaRatio < 0.04 || areaRatio > 0.95 || cropW < 50 || cropH < 50) {
        resolve(null);
        return;
      }

      addLog(`✂ Crop variance: x=${cropX} y=${cropY} w=${cropW} h=${cropH} (${Math.round(areaRatio*100)}%)`);
      const out = document.createElement('canvas');
      out.width = cropW; out.height = cropH;
      out.getContext('2d').drawImage(img, cropX, cropY, cropW, cropH, 0, 0, cropW, cropH);
      out.toBlob(blob => resolve(blob), 'image/png');
    };
    img.onerror = () => resolve(null);
    img.src = url;
  });
}


// ── IMAGE PREPROCESSING ──────────────────────────────────────
/**
 * Tiền xử lý ảnh trước khi OCR: grayscale → tăng contrast → sharpen → upscale 2x
 * Trả về một Blob (image/png) đã được enhance.
 */
async function preprocessImageForOCR(file, scale = 2.0) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const srcW = img.naturalWidth;
      const srcH = img.naturalHeight;
      const dstW = Math.round(srcW * scale);
      const dstH = Math.round(srcH * scale);

      // ── Bước 1: Vẽ ảnh gốc vào canvas tạm ──
      const src = document.createElement('canvas');
      src.width = srcW; src.height = srcH;
      const sctx = src.getContext('2d');
      sctx.drawImage(img, 0, 0);
      const srcData = sctx.getImageData(0, 0, srcW, srcH);
      const d = srcData.data;

      // ── Bước 2: Grayscale + contrast boost ──
      for (let i = 0; i < d.length; i += 4) {
        // Grayscale (luminance-weighted)
        let gray = 0.299 * d[i] + 0.587 * d[i+1] + 0.114 * d[i+2];
        // Contrast stretch: S-curve (giống CLAHE đơn giản)
        // Factor 1.8 tăng contrast, 128 là điểm giữa
        const factor = 1.8;
        gray = Math.min(255, Math.max(0, factor * (gray - 128) + 128));
        d[i] = d[i+1] = d[i+2] = gray;
      }
      sctx.putImageData(srcData, 0, 0);

      // ── Bước 3: Sharpen dùng convolution kernel ──
      const sharp = document.createElement('canvas');
      sharp.width = srcW; sharp.height = srcH;
      const shCtx = sharp.getContext('2d');
      shCtx.filter = 'none';
      shCtx.drawImage(src, 0, 0);
      const shData = shCtx.getImageData(0, 0, srcW, srcH);
      const sd = shData.data;
      const kernel = [
         0, -1,  0,
        -1,  5, -1,
         0, -1,  0
      ];
      const srcPx = new Uint8ClampedArray(d); // copy grayscale pixels
      for (let y = 1; y < srcH - 1; y++) {
        for (let x = 1; x < srcW - 1; x++) {
          let sum = 0;
          for (let ky = -1; ky <= 1; ky++) {
            for (let kx = -1; kx <= 1; kx++) {
              const idx = ((y + ky) * srcW + (x + kx)) * 4;
              sum += srcPx[idx] * kernel[(ky + 1) * 3 + (kx + 1)];
            }
          }
          const outIdx = (y * srcW + x) * 4;
          const val = Math.min(255, Math.max(0, sum));
          sd[outIdx] = sd[outIdx+1] = sd[outIdx+2] = val;
          sd[outIdx+3] = 255;
        }
      }
      shCtx.putImageData(shData, 0, 0);

      // ── Bước 4: Upscale lên scale x ──
      const dst = document.createElement('canvas');
      dst.width = dstW; dst.height = dstH;
      const dctx = dst.getContext('2d');
      dctx.imageSmoothingEnabled = true;
      dctx.imageSmoothingQuality = 'high';
      dctx.drawImage(sharp, 0, 0, dstW, dstH);

      dst.toBlob(blob => resolve(blob), 'image/png');
    };
    img.onerror = reject;
    img.src = url;
  });
}


/**
 * Chạy Tesseract với 1 psm mode.
 * Trả về object { rawText, words } — words đã lọc confidence > 50.
 */
async function runTesseract(fileOrBlob, psmMode) {
  const { data } = await Tesseract.recognize(fileOrBlob, 'eng', {
    logger: () => {},
    tessedit_char_whitelist: 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789',
    tessedit_pageseg_mode: String(psmMode),
  });

  // Lọc word-level confidence > 50 (giống Python confidence > 0.5)
  const highConfWords = (data.words || [])
    .filter(w => w.confidence > 50)
    .map(w => w.text)
    .join(' ');

  return {
    rawText: data.text || '',
    filteredText: highConfWords,
  };
}

async function buildImageVariants(file) {
  return new Promise((resolve) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const MAX = 1800;
      let W = img.naturalWidth, H = img.naturalHeight;
      if (Math.max(W, H) > MAX) {
        const r = MAX / Math.max(W, H);
        W = Math.round(W * r); H = Math.round(H * r);
      }
      const canvas = document.createElement('canvas');
      canvas.width = W; canvas.height = H;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, W, H);
      canvas.toBlob(blob => {
        if (blob) resolve([{ name: 'Original', blob }]);
        else resolve([]);
      }, 'image/jpeg', 0.90);
    };
    img.onerror = () => resolve([]);
    img.src = url;
  });
}

async function runServerOcr(blob) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 70000);
  try {
    const formData = new FormData();
    formData.append('image', blob, 'image.jpg');
    const res = await fetch('/api/ocr', { method: 'POST', body: formData, signal: controller.signal });
    if (!res.ok) throw new Error('Server status: ' + res.status);
    return await res.json();
  } finally {
    clearTimeout(timeoutId);
  }
}

const SERIAL_NOISE_PREFIXES = [
  'MODEL','DATE','POWER','STATUS','INPUT','OUTPUT','TYPE','CLASS','PART',
  'ITEM','REV','VER','VOLT','AMP','HZ','LOT','PN','FCC','IMEI','SIM',
  'NOKIA','MADE','FINLAND','VIETNAM','MANUFACTURE','AUTOMATIC','TRANSFER','SWITCH'
];

function isLikelySerialCandidate(value) {
  const candidate = String(value || '').toUpperCase();
  // Excel là nguồn xác nhận cuối cùng: cho phép cả SN chỉ gồm số hoặc có
  // dạng giống ngày nếu chuỗi đó thực sự nằm trong danh sách đã tải.
  if (Array.isArray(allSNList) && allSNList.includes(candidate)) return true;
  if (candidate.length < 6 || candidate.length > 30 || !/\d/.test(candidate)) return false;
  if (/^(?:19|20)\d{6}$/.test(candidate)) return false;
  if (SERIAL_NOISE_PREFIXES.some(prefix => candidate.startsWith(prefix))) return false;
  const digitCount = (candidate.match(/\d/g) || []).length;
  // Nới hơn bản cũ (8 ký tự / 30% chữ số) để SN ngắn hoặc ít số không bị
  // bỏ sót; Excel vẫn là nguồn xác nhận cuối cùng.
  return digitCount >= 2 && digitCount / candidate.length >= 0.15;
}

function extractCandidates(text, noiseWords = []) {
  const noise = new Set(noiseWords.map(value => String(value).toUpperCase()));
  return [...new Set((text.toUpperCase().match(/[A-Z0-9]+(?:\.[A-Z0-9]+)*/g) || [])
    .filter(value => isLikelySerialCandidate(value) && !noise.has(value)))];
}

async function triggerOCR(file) {
  setBadge2('running', '⏳ Quét mã...');
  addLog('🔍 Bắt đầu quét: Barcode → Server OCR (tối ưu 1 request)...');
  _ocrResult = null;

  const noiseWords = [
    'NOKIA','MADE','FINLAND','PMU','STATUS','INDOOR','USE','ONLY',
    'VIETNAM','AUTOMATIC','TRANSFER','SWITCH','MODEL','ANPHAT','JSC',
    'MANUFACTURE','DATE','SERIAL','SN','POWER'
  ];

  // ── Bước 1: Barcode client-side ────────────
  setBadge2('running', '⏳ Bước 1: Barcode...');
  if (typeof Html5Qrcode !== 'undefined') {
    try {
      const res = await Html5Qrcode.scanFileV2(file, true);
      if (res && res.decodedText) {
        const clean = res.decodedText.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
        // Chỉ kết thúc ở barcode phía trình duyệt khi khớp tuyệt đối Excel.
        // Các barcode gần đúng (như "NO" dính vào S-No) tiếp tục sang ocr.py
        // để quét riêng vùng SN giữa ảnh slot 2.
        if (allSNList.includes(clean)) {
          addLog(`✅ Barcode: ${clean}`);
          setBadge2('found', `✅ ${clean}`);
          _ocrResult = { rawText: clean, candidates: [clean] };
          return;
        }
      }
    } catch (_) { /* không có barcode */ }
  }
  addLog('⚠ Không có barcode, chuyển sang OCR...');

  // Giảm ảnh camera lớn trước khi upload; server vẫn dùng cùng ocr.py và
  // tự xử lý các biến thể xoay/tăng tương phản ở phía backend.
  const ocrFile = await prepareOcrImage(file);
  const variants = [{ name: 'Quét sâu (ocr.py)', blob: ocrFile }];

  // ── Bước 3: OCR từng biến thể (fast-exit khi khớp Excel) ──────
  const allCandidates = new Set();
  
  for (const { name, blob } of variants) {
    setBadge2('running', `⏳ Server OCR: ${name}...`);
    addLog(`🔍 Server OCR biến thể [${name}]...`);

    let cands = [];
    try {
      // Gọi RapidOCR/PaddleOCR; lỗi engine cũng kích hoạt fallback.
      const data = await runServerOcr(blob);
      if (!data.success) throw new Error(data.error || 'OCR không khả dụng');
      if (data.success && data.candidates) {
        cands = data.candidates;
        addLog(`📄 Server OCR [${name}] raw: ${data.rawText.replace(/\n/g,'|').substring(0,100)}`);
      }
    } catch (e) {
      addLog(`⚠ Lỗi Server OCR [${name}]: ${e.message} → Fallback Tesseract.js`);
      // Fallback Tesseract.js (WASM) nếu server lỗi
      if (typeof Tesseract !== 'undefined') {
        try {
          const r11 = await runTesseract(blob, 11).catch(() => ({ rawText: '', filteredText: '' }));
          const textToUse = r11.filteredText || r11.rawText;
          if (textToUse) addLog(`📄 Tesseract.js [${name}] raw: ${textToUse.replace(/\n/g,'|').substring(0,100)}`);
          cands = extractCandidates(textToUse, noiseWords);
        } catch (e2) {}
      }
    }

    if (cands.length) {
      addLog(`📋 [${name}]: ${cands.join(', ')}`);
      cands.forEach(c => allCandidates.add(c));

      // Fast-exit: nếu tìm thấy khớp Excel ngay, trả về luôn
      if (allSNList.length) {
        for (const c of cands) {
          if (allSNList.includes(c)) {
            addLog(`⚡ Khớp Excel ngay: "${c}" (biến thể: ${name})`);
            setBadge2('found', `✅ ${c}`);
            _ocrResult = { rawText: c, candidates: [c] };
            return;
          }
        }

      }
    }
  }

  // ── Bước 4: Tổng hợp kết quả (không có fast-exit) ─────────────
  const rawCandidates = [...allCandidates];

  if (rawCandidates.length) {
    setBadge2('found', `🔍 ${rawCandidates.length} chuỗi tìm thấy`);
    addLog(`📊 Kết quả quét sâu: ${rawCandidates.join(', ')}`);
  } else {
    setBadge2('fail', '❌ Không nhận ra chữ');
    addLog('⚠ OCR không nhận dạng được chuỗi SN nào từ bất kỳ biến thể nào');
  }

  _ocrResult = { rawText: rawCandidates[0] || '', candidates: rawCandidates };
}





function setBadge2(type, text) {
  const p = $('preview2');
  let badge = p.querySelector('.ocr-badge');
  if (!badge) {
    badge = document.createElement('div');
    badge.className = 'ocr-badge';
    p.appendChild(badge);
  }
  badge.className = `ocr-badge ocr-${type}`;
  badge.textContent = text;
}

/** Tính tỉ lệ khớp Levenshtein (0.0 -> 1.0) */
function levenshteinRatio(s1, s2) {
  if (s1 === s2) return 1.0;
  if (!s1 || !s2) return 0.0;
  const m = s1.length, n = s2.length;
  const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = s1[i - 1] === s2[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost);
    }
  }
  return 1 - (dp[m][n] / Math.max(m, n));
}

/** Sliding Window + Fuzzy Match — giống Python smart_match_serial */
function smartMatchSerial(textCandidate, excelData) {
  if (!textCandidate || textCandidate.length < 9) return null;
  const upper = textCandidate.toUpperCase().replace(/[^A-Z0-9]/g, '');
  const n = upper.length;

  // 1. Kiểm tra chính xác toàn chuỗi trước
  if (excelData.includes(upper)) return { match: upper, score: 100 };

  // 2. Sliding window: cắt mọi đoạn con từ dài nhất đến 9
  for (let len = n - 1; len >= 9; len--) {
    for (let start = 0; start <= n - len; start++) {
      const sub = upper.substring(start, start + len);
      // Bỏ qua nếu đoạn con không có số (số là bắt buộc trong SN)
      if (!/[0-9]/.test(sub)) continue;

      // A. Khớp chính xác
      if (excelData.includes(sub)) return { match: sub, score: 100 };

      // B. Fuzzy match với ngưỡng 80%
      let bestMatch = null, bestRatio = 0;
      for (const sn of excelData) {
        const ratio = levenshteinRatio(sub, sn);
        if (ratio > bestRatio) { bestRatio = ratio; bestMatch = sn; }
      }
      if (bestRatio >= 0.80) return { match: bestMatch, score: Math.round(bestRatio * 100) };
    }
  }
  return null;
}

async function openSnModal() {
  state.selectedSN = '';
  $('snSelectedDisplay').classList.add('hidden');
  $('confirmSnBtn').disabled = true;
  $('snSearchInput').value = '';
  $('ocrCandidates').innerHTML = '';
  $('ocrRawBox').style.display = 'none';
  $('snModal').classList.remove('hidden');

  // Click outside to deselect
  $('snModal').onclick = (e) => { if (e.target.id === 'snModal') clearSNSelection(); };
  $('snListBox').onclick = (e) => { if (e.target.id === 'snListBox') clearSNSelection(); };
  $('ocrCandidates').onclick = (e) => { if (e.target.id === 'ocrCandidates') clearSNSelection(); };

  // Hiển thị ảnh Zoom bên trái
  const imgEl = $('imgEl2');
  $('snZoomImg').innerHTML = imgEl
    ? `<img src="${imgEl.src}" alt="Ảnh Zoom"/>`
    : '<span class="placeholder-text">Chưa có ảnh Zoom (slot 2)</span>';

  // Load Excel SN list
  try {
    const res  = await fetch('/api/excel/list-sn');
    const data = await res.json();
    allSNList = data.sns || [];
    $('snTotalCount').textContent = allSNList.length;
    renderSNList(allSNList);
  } catch (e) {
    $('snListBox').innerHTML = `<div class="folder-empty">❌ ${e.message}</div>`;
  }

  // Click outside in snListBox -> deselect
  $('snListBox').onclick = (e) => {
    if (e.target.id === 'snListBox') clearSNSelection();
  };
  $('ocrCandidates').onclick = (e) => {
    if (e.target.id === 'ocrCandidates') clearSNSelection();
  };

  // Hiển thị OCR results
  renderOCRResults();
}

function renderOCRResults() {
  const section = $('ocrSection');
  const statusEl = $('ocrStatusText');
  const candBox  = $('ocrCandidates');
  const rawBox   = $('ocrRawBox');

  if (!_ocrResult) {
    statusEl.innerHTML = typeof Tesseract !== 'undefined'
      ? '⏳ OCR đang chạy... (vui lòng chờ)'
      : '⚠ OCR chưa sẵn sàng';
    return;
  }

  // Raw text
  if (_ocrResult.rawText) {
    rawBox.textContent = _ocrResult.rawText;
    rawBox.style.display = 'block';
  }

  if (!_ocrResult.candidates.length) {
    statusEl.innerHTML = '❌ <b>Không nhận dạng được SN rõ từ ảnh</b> — vui lòng tìm trong Excel bên dưới';
    candBox.innerHTML  = '<div class="ocr-no-match">Ảnh bị nhòe hoặc SN không rõ. Hãy tìm SN trong danh sách Excel.</div>';
    return;
  }

  statusEl.innerHTML = `🔢 <b>Nhận dạng được ${_ocrResult.candidates.length} chuỗi</b> — so sánh với Excel:`;
  candBox.innerHTML  = '';

  // Tính match score cho mỗi candidate
  const rows = _ocrResult.candidates.map(cand => {
    // 1. Khớp chính xác
    if (allSNList.includes(cand)) {
      return { cand, best: cand, score: 100 };
    }
    
    // 2. Smart Match (Sliding Window + Fuzzy)
    const smart = smartMatchSerial(cand, allSNList);
    if (smart) {
      return { cand, best: smart.match, score: smart.score };
    }
    
    // 3. Normal Fuzzy
    let best = null, bestScore = 0;
    for (const sn of allSNList) {
      const s = Math.round(levenshteinRatio(cand, sn) * 100);
      if (s > bestScore) { bestScore = s; best = sn; }
    }
    return { cand, best, score: bestScore };
  }).sort((a, b) => b.score - a.score);

  rows.forEach(({ cand, best, score }) => {
    const badgeCls = score === 100 ? 'match-100' : score >= 60 ? 'match-hi' : 'match-lo';
    const badgeTxt = score === 100 ? `✅ ${score}% Khớp`
                   : score >= 60  ? `⚠ ${score}% Gần khớp`
                   : `❌ ${score}% Không khớp`;
    const excelTxt = best && allSNList.length
      ? (score === 100 ? `→ Khớp: ${best}` : score >= 40 ? `→ Gần nhất: ${best}` : '→ Không có trong Excel')
      : (allSNList.length ? '→ Không tìm thấy' : '→ Chưa load Excel');

    const row = document.createElement('div');
    row.className = 'ocr-candidate-row';
    row.innerHTML = `
      <span class="ocr-cand-text">${cand}</span>
      <span class="ocr-cand-excel">${excelTxt}</span>
      <span class="match-badge ${badgeCls}">${badgeTxt}</span>`;
    // Click → chọn SN tốt nhất (nếu khớp Excel) hoặc chính candidate
    const chosenSN = (score >= 60 && best) ? best : cand;
    row.onclick = () => {
      document.querySelectorAll('.ocr-candidate-row').forEach(r => r.classList.remove('selected'));
      row.classList.add('selected');
      selectSN(chosenSN);
    };
    // Nếu 100% → auto-select
    if (score === 100 && !state.selectedSN) {
      setTimeout(() => row.click(), 100);
    }
    candBox.appendChild(row);
  });
}

// ── SMART MATCH ────────────────────────────────────────────────
function smartMatchSerial(cand, excelList) {
  if (!cand || cand.length < 9) return null;
  const n = cand.length;
  const minLen = 9;
  for (let len = n; len >= minLen; len--) {
    for (let start = 0; start <= n - len; start++) {
      let sub = cand.substring(start, start + len);
      if (!/\\d/.test(sub)) continue;
      let cleanSub = sub.replace(/[^A-Z0-9]/g, '');
      if (excelList.includes(cleanSub)) {
        return { match: cleanSub, score: 100 };
      }
      
      let bestFuzzy = null;
      let bestScore = 0;
      for (const sn of excelList) {
        const s = Math.round(levenshteinRatio(cleanSub, sn) * 100);
        if (s > bestScore) { bestScore = s; bestFuzzy = sn; }
      }
      if (bestScore >= 80) {
        return { match: bestFuzzy, score: bestScore };
      }
    }
  }
  return null;
}

function closeSnModal() { $('snModal').classList.add('hidden'); }

function filterSNList(keyword) {
  const kw = keyword.trim().toUpperCase();
  renderSNList(kw ? allSNList.filter(s => s.includes(kw)) : allSNList);
}

function renderSNList(list) {
  const box = $('snListBox');
  if (!list.length) { box.innerHTML = '<div class="folder-empty">Không tìm thấy SN nào</div>'; return; }
  box.innerHTML = '';
  list.forEach(sn => {
    const item = document.createElement('div');
    item.className = 'sn-item' + (sn === state.selectedSN ? ' sn-sel' : '');
    item.innerHTML = `<span class="sn-item-dot"></span>${sn}`;
    item.onclick = () => { selectSN(sn); document.querySelectorAll('.sn-item').forEach(e => e.classList.remove('sn-sel')); item.classList.add('sn-sel'); };
    box.appendChild(item);
  });
}

function selectSN(sn) {
  state.selectedSN = sn;
  $('snSelectedDisplay').classList.remove('hidden');
  $('snSelectedValue').textContent = sn;
  $('confirmSnBtn').disabled = false;
  // Không tự động điền vào ô Serial Thủ Công nữa
}

function clearSNSelection() {
  state.selectedSN = '';
  $('snSelectedDisplay').classList.add('hidden');
  $('confirmSnBtn').disabled = true;
  document.querySelectorAll('.sn-item, .ocr-candidate-row, .ocr-cand-row').forEach(e => {
    e.classList.remove('sn-sel');
    e.classList.remove('selected');
  });
}

async function confirmSN() {
  if (!state.selectedSN) return;
  closeSnModal();
  await doExport(state.selectedSN);
}

async function skipSnCheck() {
  closeSnModal();
  await doExport('');
}

// ── FUZZY MODAL ───────────────────────────────────────────────
let _fuzzyZoom = 1, _fuzzyPan = { x:0, y:0 }, _fuzzyDrag = null;

function openFuzzyModal(scannedCandidate, fuzzyMatches) {
  const msgBox   = $('fuzzyMessage');
  const container = $('fuzzyRadioContainer');
  const zoomDiv  = $('fuzzyZoomImg');
  const candTable = $('fuzzyCandTable');
  const manualInput = $('fuzzyManualInput');

  // Reset zoom/pan
  _fuzzyZoom = 1; _fuzzyPan = { x: 0, y: 0 };
  fuzzyApplyTransform();

  // ── Ảnh Zoom (slot 2) ──
  const imgEl = $('imgEl2');
  if (imgEl && imgEl.src && imgEl.src !== window.location.href) {
    zoomDiv.innerHTML = `<img src="${imgEl.src}" alt="Zoom" draggable="false"
      style="max-width:100%; max-height:400px; object-fit:contain; display:block; user-select:none;"/>`;
  } else {
    zoomDiv.innerHTML = '<span style="color:#555;font-size:0.85em;">Chưa có ảnh Zoom (slot 2)</span>';
  }

  // ── Thông báo ──
  msgBox.textContent = scannedCandidate
    ? `SN quét được: ${scannedCandidate}. ${state.excelLoaded ? 'Không khớp chính xác Excel.' : 'Chưa tải Excel để đối chiếu.'} Chọn SN trước khi xuất Word.`
    : 'Không đọc được SN từ ảnh. Nhập SN hoặc tìm trong danh sách Excel trước khi xuất Word.';

  // Xóa ô nhập tay
  if (manualInput) manualInput.value = '';

  // ── Danh sách radio (gợi ý fuzzy) ──
  container.innerHTML = '';

  // Option 1: mã gốc quét ra
  const makeLabel = (value, labelHtml, checked = false) => {
    const lbl = document.createElement('label');
    lbl.style.cssText = 'display:flex;align-items:center;gap:10px;cursor:pointer;padding:5px 4px;border-radius:4px;';
    lbl.innerHTML = `<input type="radio" name="fuzzySelection" value="${value}" ${checked ? 'checked' : ''}>${labelHtml}`;
    return lbl;
  };

  if (scannedCandidate) container.appendChild(makeLabel(
    scannedCandidate,
    `<span style="color:#bbb;font-size:0.9em;">${scannedCandidate}
      <i style="color:#666;font-size:0.85em;"> (SN quét gốc)</i></span>`, true
  ));

  // Deduplicate & sort
  const uniqueMatches = [];
  const seen = new Set();
  fuzzyMatches.sort((a, b) => b.score - a.score).forEach(m => {
    if (m.best && !seen.has(m.best)) { seen.add(m.best); uniqueMatches.push(m); }
  });

  uniqueMatches.forEach((m, idx) => {
    container.appendChild(makeLabel(
      m.best,
      `<span style="color:#64b5f6;font-size:0.9em;">${m.best}
        <i style="color:#888;font-size:0.85em;"> (Gợi ý Excel — Giống ${m.score}%)</i></span>`,
      false
    ));
  });

  if (!uniqueMatches.length) {
    const empty = document.createElement('div');
    empty.textContent = 'SN tương tự trong Excel: không tìm thấy gợi ý đạt 80%.';
    container.appendChild(empty);
  }

  // ── Bảng OCR candidates ──
  fuzzyRenderCandidates('');

  $('fuzzyModal').classList.remove('hidden');
}

// Render bảng SN candidates từ OCR, lọc theo keyword
// Render bảng SN candidates: tìm kiếm trong allSNList khi user nhập, + hiển OCR candidates
function fuzzyRenderCandidates(keyword) {
  const candTable = $('fuzzyCandTable');
  if (!candTable) return;
  const kw = keyword.trim().toUpperCase();

  let displayList = [];

  if (kw.length >= 3) {
    // Tìm trong allSNList (Excel) theo fuzzy / substring
    const exact   = allSNList.filter(s => s.includes(kw));
    const fuzzyHi = [];
    if (exact.length < 20) {
      // Thêm các kết quả fuzzy nếu exact không đủ
      for (const sn of allSNList) {
        if (exact.includes(sn)) continue;
        const r = levenshteinRatio(kw, sn);
        if (r >= 0.65) fuzzyHi.push({ sn, score: Math.round(r * 100) });
      }
      fuzzyHi.sort((a, b) => b.score - a.score);
    }
    // Gộp: exact trước, fuzzy sau
    displayList = [
      ...exact.map(s => ({ sn: s, label: '✅ Khớp', color: '#4caf50', bg: '#1a3a1a' })),
      ...fuzzyHi.slice(0, 10).map(x => ({ sn: x.sn, label: `~${x.score}%`, color: '#ff9800', bg: '#2a1a00' }))
    ];
  } else {
    // Không có keyword -> hiển OCR candidates (nếu có)
    const ocrCands = (_ocrResult && _ocrResult.candidates) || [];
    displayList = ocrCands.map(s => {
      const inExcel = allSNList.includes(s);
      return { sn: s,
        label: inExcel ? '✅ Có Excel' : '⚠ Không có Excel',
        color: inExcel ? '#4caf50' : '#888',
        bg:    inExcel ? '#1a3a1a' : '#2a1a1a' };
    });
  }

  if (!displayList.length) {
    candTable.innerHTML = `<div style="color:#555;font-size:0.8em;padding:4px;">${kw.length >= 3 ? 'Không tìm thấy trong Excel' : 'Không có gợi ý từ OCR'}</div>`;
    return;
  }

  candTable.innerHTML = '';
  displayList.forEach(({ sn, label, color, bg }) => {
    const item = document.createElement('div');
    item.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:5px 8px;background:#1a1a28;border-radius:4px;cursor:pointer;border:1px solid #2a2a3a;';
    item.innerHTML = `
      <span style="font-family:monospace;font-size:0.9em;color:#ccc;">${sn}</span>
      <span style="font-size:0.75em;padding:2px 6px;border-radius:3px;background:${bg};color:${color};">${label}</span>`;
    item.onmouseenter = () => item.style.background = '#252535';
    item.onmouseleave = () => item.style.background = '#1a1a28';
    item.onclick = () => {
      const inp = $('fuzzyManualInput');
      if (inp) { inp.value = sn; fuzzyRenderCandidates(sn); }
      const radio = document.querySelector(`input[name="fuzzySelection"][value="${sn}"]`);
      if (radio) radio.checked = true;
    };
    candTable.appendChild(item);
  });
}

function fuzzyFilterCandidates(kw) {
  fuzzyRenderCandidates(kw);
}

// ── Zoom / Pan ──
function fuzzyApplyTransform() {
  const el = $('fuzzyZoomImg');
  if (el) el.style.transform = `translate(${_fuzzyPan.x}px, ${_fuzzyPan.y}px) scale(${_fuzzyZoom})`;
}
function fuzzyZoomWheel(e) {
  e.preventDefault();
  _fuzzyZoom = Math.min(8, Math.max(0.5, _fuzzyZoom * (e.deltaY < 0 ? 1.15 : 0.87)));
  fuzzyApplyTransform();
}
function fuzzyZoomReset() { _fuzzyZoom = 1; _fuzzyPan = { x: 0, y: 0 }; fuzzyApplyTransform(); }
function fuzzyPanStart(e) {
  if (e.button !== 0) return;
  const wrap = $('fuzzyZoomWrap');
  if (wrap) wrap.style.cursor = 'grabbing';
  const startX = e.clientX - _fuzzyPan.x, startY = e.clientY - _fuzzyPan.y;
  const onMove = ev => { _fuzzyPan.x = ev.clientX - startX; _fuzzyPan.y = ev.clientY - startY; fuzzyApplyTransform(); };
  const onUp   = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); if (wrap) wrap.style.cursor = 'grab'; };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

function closeFuzzyModal() {
  $('fuzzyModal').classList.add('hidden');
}

function confirmFuzzy() {
  const selected = document.querySelector('input[name="fuzzySelection"]:checked');
  if (!selected) {
    alert("Vui lòng chọn 1 Serial Number!");
    return;
  }
  closeFuzzyModal();
  doExport(selected.value);
}

function confirmFuzzyManual() {
  const val = ($('fuzzyManualInput')?.value || '').trim().toUpperCase();
  if (!val) { alert('Vui lòng nhập số Serial!'); return; }
  closeFuzzyModal();
  doExport(val);
}



// ── UTILITIES ─────────────────────────────────────────────────
function addLog(msg) {
  const box  = $('logBox');
  const line = document.createElement('div');
  const time = new Date().toLocaleTimeString('vi-VN',
    { hour:'2-digit', minute:'2-digit', second:'2-digit' });
  line.textContent = `[${time}] ${msg}`;
  box.appendChild(line);
  box.scrollTop = box.scrollHeight;
}

function setStatusMsg(msg, type) {
  const el = $('statusMsg');
  el.textContent = msg;
  el.style.color = type === 'ok' ? '#22c55e' : type === 'err' ? '#ef4444' : '#eab308';
  setTimeout(() => { el.textContent = `Tiến trình: ${parseInt($('progressBar').style.width)}%`; el.style.color = ''; }, 4000);
}

function showLoading(text = 'Đang xử lý...') {
  $('loadingText').textContent = text;
  $('loadingOverlay').classList.remove('hidden');
}
function hideLoading() { $('loadingOverlay').classList.add('hidden'); }

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a   = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a);
  // Safari/iOS cần thêm thời gian đọc Blob sau click; revoke ngay lập tức
  // khiến file Word không mở hoặc tải được trên điện thoại.
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

function getFilename(response) {
  const cd = response.headers.get('content-disposition');
  if (!cd) return null;
  const m  = cd.match(/filename\*?=['"]?(?:UTF-8'')?([^'";\n]+)/i);
  return m ? decodeURIComponent(m[1]) : null;
}
