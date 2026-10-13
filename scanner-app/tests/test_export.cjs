const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = readFileSync('src/main/resources/static/js/app.js', 'utf8');
const elements = {};
const storage = new Map();
const context = vm.createContext({
  console,
  window: {addEventListener() {}},
  File: class File { constructor(parts, name, options) { this.name = name; this.type = options?.type || ''; this.size = 1; } },
  localStorage: {getItem: k => storage.get(k), setItem: (k,v) => storage.set(k,v)},
  document: {
    addEventListener() {},
    getElementById(id) { return elements[id] ||= {value:'', disabled:false}; },
    querySelector() { return {value:'TRUOC_SCC'}; }
  },
  confirm: () => true,
  fetch: async () => ({ok:true, json:async () => ({sns:['094771129401']})}),
});
vm.runInContext(source, context);
vm.runInContext(`
  addLog = setStatusMsg = showLoading = hideLoading = () => {};
  state.images = {1:{},2:{},3:{},4:{}};
  state.excelLoaded = true;
  for (const id of ['deviceName','productCode','poNumber']) $(id).value = 'test';
  globalThis.events = [];
  globalThis.result = ['N0094771129401'];
  triggerOCR = async () => { events.push('scan'); _ocrResult = {candidates:result}; };
  doExport = async sn => events.push(['export',sn]);
  openFuzzyModal = (raw, suggestions) => events.push(['modal',raw,suggestions]);
`, context);
(async () => {
  await vm.runInContext('exportReport()', context);
  let events = JSON.parse(JSON.stringify(context.events));
  assert.equal(events[0], 'scan');
  assert.equal(events[1][0], 'modal');
  assert.equal(events[1][1], 'N0094771129401');
  assert.equal(events[1][2][0].best, '094771129401');
  assert.ok(events[1][2][0].score >= 80);
  vm.runInContext("events=[]; result=['094771129401']", context);
  await vm.runInContext('exportReport()', context);
  assert.deepEqual(JSON.parse(JSON.stringify(context.events)), ['scan',['export','094771129401']]);
  vm.runInContext('events=[]; result=[]', context);
  await vm.runInContext('exportReport()', context);
  assert.equal(context.events[1][0], 'modal');
  assert.equal(context.events[1][1], '');
  vm.runInContext("rememberPickerPath('D:/ẢNH THIẾT BỊ/Mặt trước')", context);
  assert.equal(storage.get('scanner.lastFolder34'), undefined);
  assert.ok(source.includes('lastImageFolder12'));
  assert.ok(source.includes('function compressImageForWord'));
  assert.ok(source.includes("await doExport(exactMatch, { verifiedFromExcel: true })"));
  assert.ok(!source.includes("localStorage.setItem('scanner.lastFolder12'"));
  assert.ok(!source.includes("localStorage.setItem('scanner.lastName12'"));
  assert.ok(!source.includes('if (slot === 2) triggerOCR(file)'));
  console.log('PASS: exact export, fuzzy confirmation, no-read dialog, folder memory, no upload scan');
})().catch(error => { console.error(error); process.exitCode = 1; });
