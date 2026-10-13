const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const elements = new Map();
function element() {
  return {
    value: '', textContent: '', innerHTML: '', style: {}, disabled: false, open: false,
    children: [], classList: { add() {}, remove() {} },
    appendChild(child) { this.children.push(child); },
    showModal() { this.open = true; }, focus() {},
  };
}
const context = vm.createContext({
  console, window: { addEventListener() {} }, location: { hostname: 'localhost' },
  document: {
    addEventListener() {}, createElement: element, querySelectorAll: () => [],
    getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
    querySelector: () => ({ value: 'TRUOC_SCC' }),
  },
  FormData: class { append() {} },
  fetch: async () => { throw new Error('Unexpected request'); },
});
vm.runInContext(readFileSync('src/main/resources/static/js/app.js', 'utf8'), context);
vm.runInContext(`
  addLog = setStatusMsg = showLoading = () => {};
  globalThis.hiddenCount = 0;
  hideLoading = () => hiddenCount++;
`, context);

(async () => {
  const requests = [];
  context.fetch = async url => {
    requests.push(url);
    return { ok: true, json: async () => url === '/api/storage/config'
      ? {success:true, nasMode:true, imagesRoot:'NAS/images', reportsRoot:'NAS/reports'}
      : {success:false, cancelled:true} };
  };
  await vm.runInContext("openBrowser('save')", context);
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(requests.some(url => url.includes('/api/native-pick-folder?initial=C%3A%5C')));

  // Máy khác/iPhone không thể mở Explorer Windows nên vẫn dùng bộ chọn web.
  context.location.hostname = '192.168.1.50';
  context.fetch = async url => {
    requests.push(url);
    return { ok: true, json: async () => ({success:true, current:'NAS/images', parent:'', entries:[]}) };
  };
  await vm.runInContext("openBrowser('folder3')", context);
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(requests.some(url => url.includes('purpose=images&path=NAS%2Fimages')));

  // A stale response must not replace the currently opened folder.
  const pending = [];
  context.fetch = () => new Promise(resolve => pending.push(resolve));
  const old = vm.runInContext("browseTo('old')", context);
  const fresh = vm.runInContext("browseTo('fresh')", context);
  pending[1]({json: async () => ({success:true,current:'fresh',parent:'',entries:[]})});
  await fresh;
  pending[0]({json: async () => ({success:true,current:'old',parent:'',entries:[]})});
  await old;
  assert.equal(vm.runInContext('_selectedFolderPath', context), 'fresh');

  context.fetch = async () => ({status:409, ok:false,
    headers:{get:()=>'application/json'},
    json:async () => ({success:false,code:'FILE_EXISTS',filename:'TrcSC_123.docx',savedPath:'NAS/reports/TrcSC_123.docx'})});
  vm.runInContext(`
    state.pendingExport = {deviceName:'test',productCode:'test',poNumber:'PO',reportType:'TRUOC_SCC',repairNotes:''};
    showSavedToast = downloadBlob = () => {throw new Error('Must not report success on conflict');};
  `, context);
  await vm.runInContext("doExport('123')", context);
  assert.equal(elements.get('noticeTitle').textContent, 'File đã tồn tại, vui lòng kiểm tra lại');
  assert.equal(elements.get('noticeDialog').open, true);
  assert.equal(vm.runInContext('state.pendingExport.poNumber', context), 'PO');
  assert.equal(context.hiddenCount, 1);
  console.log('PASS: Windows Explorer picker locally, web picker remotely, stale-response guard, duplicate modal preserves form');
})().catch(error => { console.error(error); process.exitCode = 1; });
