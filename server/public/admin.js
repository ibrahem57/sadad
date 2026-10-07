(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const tokenKey = 'sadad-admin-session';
  let token = sessionStorage.getItem(tokenKey) || '';
  let stores = [];
  let current = null;
  let currentSnapshot = null;
  let currentBackups = [];
  let currentContactArchives = [];
  let bootstrap = false;

  const labels = { contacts: 'جهات الاتصال', debts: 'الديون', payments: 'الدفعات', reports: 'الكشوفات والتقارير', analytics: 'الإحصائيات', export: 'التصدير', whatsapp: 'WhatsApp', appLock: 'قفل التطبيق' };
  const permissionNames = Object.keys(labels);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const fmt = value => new Intl.NumberFormat('ar-PS', { maximumFractionDigits: 2 }).format(Number(value || 0));
  const englishDigits = value => String(value ?? '').replace(/[٠-٩]/g, digit => String('٠١٢٣٤٥٦٧٨٩'.indexOf(digit))).replace(/[۰-۹]/g, digit => String('۰۱۲۳۴۵۶۷۸۹'.indexOf(digit)));
  function integerInput(selector, min, max, fallback) {
    const raw = englishDigits($(selector).value).trim();
    if (!raw) return fallback;
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`أدخل عددًا صحيحًا من ${min} إلى ${max}.`);
    return value;
  }
  const dateFmt = value => value ? new Date(Number(value)).toLocaleString('ar-PS') : '—';
  const modeName = mode => ({ permanent: 'فعال دائمًا', timed: 'مدة محددة', paused: 'موقوف' }[mode] || 'غير محدد');
  function duration(value) {
    if (value == null) return 'دائم';
    const ms = Math.max(0, Number(value));
    if (!ms) return 'منتهي';
    const days = Math.floor(ms / 86400000), hours = Math.floor(ms % 86400000 / 3600000);
    return days ? `${fmt(days)} يوم و${fmt(hours)} ساعة` : `${fmt(hours)} ساعة`;
  }
  function notice(message, error = false) {
    const node = $('#notice'); node.textContent = message; node.classList.remove('hidden', 'error');
    if (error) node.classList.add('error');
    setTimeout(() => node.classList.add('hidden'), 5500);
  }
  async function api(path, options = {}) {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(path, { ...options, headers });
    const data = await response.json().catch(() => ({ ok: false, message: 'استجابة الخادم غير مفهومة.' }));
    if (!response.ok || data.ok === false) {
      if (response.status === 401 && token) { sessionStorage.removeItem(tokenKey); token = ''; showAuth(false); }
      throw new Error(data.message || 'تعذر تنفيذ العملية.');
    }
    return data;
  }
  function showAuth(setup, passwordChange = false) {
    bootstrap = setup; $('#authView').classList.remove('hidden'); $('#appView').classList.add('hidden');
    $('#authForm').classList.toggle('hidden', passwordChange); $('#adminPasswordChangeForm').classList.toggle('hidden', !passwordChange);
    $('#setupKeyWrap').classList.toggle('hidden', !setup); $('#authTitle').textContent = passwordChange ? 'غيّر كلمة مرور الأدمن' : setup ? 'إنشاء مدير النظام' : 'دخول الأدمن';
    $('#authHint').textContent = passwordChange ? 'عيّن كلمة مرور جديدة من 4 خانات أو أكثر للمتابعة.' : setup ? 'أنشئ حساب الأدمن الأول. مفتاح التأسيس موجود في ملف البيئة على الخادم.' : 'هذه اللوحة خاصة بمدير النظام.';
    $('#authSubmit').textContent = setup ? 'تأسيس لوحة الإدارة' : 'دخول آمن'; $('#authPassword').autocomplete = setup ? 'new-password' : 'current-password';
  }
  function showApp(username, forcePasswordChange = false) {
    if (forcePasswordChange) { $('#authUsername').value = username || ''; showAuth(false, true); return; }
    $('#authView').classList.add('hidden'); $('#appView').classList.remove('hidden'); $('#adminName').textContent = username || 'أدمن';
    loadDashboard().catch(error => notice(error.message, true));
  }
  async function startup() {
    try { if (token) { const session = await api('/api/admin/session'); showApp(session.admin.username, session.forcePasswordChange); return; } } catch (_) { token = ''; sessionStorage.removeItem(tokenKey); }
    try { const status = await api('/api/setup/status'); showAuth(status.bootstrapRequired); }
    catch (error) { showAuth(false); notice(error.message, true); }
  }
  $('#authForm').addEventListener('submit', async event => {
    event.preventDefault(); $('#authError').textContent = '';
    try {
      const payload = { username: $('#authUsername').value.trim(), password: $('#authPassword').value };
      let result;
      if (bootstrap) { payload.setupKey = $('#setupKey').value; result = await api('/api/setup/admin', { method: 'POST', body: JSON.stringify(payload) }); }
      else result = await api('/api/admin/login', { method: 'POST', body: JSON.stringify(payload) });
      token = result.token; sessionStorage.setItem(tokenKey, token); showApp(result.admin.username, !!result.forcePasswordChange);
    } catch (error) { $('#authError').textContent = error.message; }
  });
  $('#adminPasswordChangeForm').addEventListener('submit', async event => {
    event.preventDefault(); $('#adminPasswordError').textContent = '';
    try { await api('/api/admin/change-password', { method: 'POST', body: JSON.stringify({ currentPassword: $('#adminCurrentPassword').value, password: $('#adminNewPassword').value }) }); $('#adminPasswordChangeForm').reset(); showApp($('#authUsername').value.trim()); }
    catch (error) { $('#adminPasswordError').textContent = error.message; }
  });
  const adminPasswordDialog = $('#adminPasswordDialog');
  $('#changeAdminPasswordBtn').addEventListener('click', () => {
    $('#adminPasswordDialogForm').reset(); $('#dialogPasswordError').textContent = ''; adminPasswordDialog.showModal();
  });
  $('#cancelAdminPassword').addEventListener('click', () => adminPasswordDialog.close());
  $('#adminPasswordDialogForm').addEventListener('submit', async event => {
    event.preventDefault(); $('#dialogPasswordError').textContent = '';
    const password = $('#dialogNewPassword').value;
    if (password !== $('#dialogConfirmPassword').value) { $('#dialogPasswordError').textContent = 'كلمتا المرور غير متطابقتين.'; return; }
    try {
      await api('/api/admin/change-password', { method: 'POST', body: JSON.stringify({ currentPassword: $('#dialogCurrentPassword').value, password }) });
      adminPasswordDialog.close(); notice('تم تغيير كلمة مرور الأدمن.');
    } catch (error) { $('#dialogPasswordError').textContent = error.message; }
  });
  $('#logoutBtn').addEventListener('click', async () => { try { await api('/api/admin/logout', { method: 'POST' }); } catch (_) {} token = ''; sessionStorage.removeItem(tokenKey); showAuth(false); });

  async function loadDashboard() {
    const [overview, list] = await Promise.all([api('/api/admin/overview'), api('/api/admin/stores')]);
    $('#adminName').textContent = overview.meta.username; stores = list.stores;
    $('#metrics').innerHTML = [['المتاجر', overview.overview.stores], ['النشطة', overview.overview.active], ['الموثقة', overview.overview.trusted], ['أصل الديون', `${fmt(overview.overview.debts)} ₪`]].map(item => `<article class="metric"><span>${item[0]}</span><b>${item[1]}</b></article>`).join('');
    renderStores();
  }
  function renderStores() {
    const query = $('#searchStores').value.trim().toLowerCase();
    const rows = stores.filter(store => `${store.name} ${store.username}`.toLowerCase().includes(query));
    $('#storesBody').innerHTML = rows.map(store => {
      const subscription = store.subscriptionMode === 'permanent' ? 'فعال دائمًا' : store.subscriptionMode === 'paused' ? 'موقوف' : store.subscriptionRemainingMs > 0 ? `متبقي ${duration(store.subscriptionRemainingMs)}` : 'منتهي';
      return `<tr><td><b>${esc(store.name)}</b>${store.verified ? '<small title="حساب موثّق" aria-label="حساب موثّق" style="display:inline-grid;place-items:center;width:17px;height:17px;margin-inline-start:7px;border-radius:50%;background:#d9f3ec;color:#087765;font-size:11px;font-weight:800;vertical-align:middle">✓</small>' : ''}</td><td>${esc(store.username)}</td><td><span class="status ${store.status === 'active' ? '' : 'suspended'}">${store.status === 'active' ? 'نشط' : 'مجمّد'}</span></td><td>${esc(subscription)}</td><td>${fmt(store.boundDevices)}/${fmt(store.maxDevices)}</td><td>${store.verified ? '<b class="verified">✓ موثوق</b>' : '—'}</td><td>${fmt(store.contacts)}</td><td>${fmt(store.debts)} · ${fmt(store.debtTotal)} ₪</td><td><button class="button quiet" data-open="${store.id}">إدارة</button></td></tr>`;
    }).join('');
    $('#emptyStores').classList.toggle('hidden', rows.length > 0);
    document.querySelectorAll('[data-open]').forEach(button => button.addEventListener('click', () => openStore(Number(button.dataset.open))));
  }
  $('#searchStores').addEventListener('input', renderStores);
  const dialog = $('#createDialog');
  $('#newStoreBtn').addEventListener('click', () => dialog.showModal()); $('#cancelCreate').addEventListener('click', () => dialog.close());
  function generatePassword() { const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%'; const bytes = new Uint32Array(18); crypto.getRandomValues(bytes); return Array.from(bytes, value => chars[value % chars.length]).join(''); }
  $('#generatePassword').addEventListener('click', () => $('#newPassword').value = generatePassword());
  $('#newSubscriptionMode').addEventListener('change', () => { $('#newSubscriptionDaysWrap').classList.toggle('hidden', $('#newSubscriptionMode').value !== 'timed'); });
  $('#createStoreForm').addEventListener('submit', async event => {
    event.preventDefault();
    try {
      const body = { name: $('#newName').value.trim(), username: $('#newUsername').value.trim(), password: $('#newPassword').value, debtorLimit: integerInput('#newLimit', 0, 1000000, 0), maxDevices: integerInput('#newMaxDevices', 1, 50, 1), subscriptionMode: $('#newSubscriptionMode').value, subscriptionDays: integerInput('#newSubscriptionDays', 1, 36500, 30) };
      const result = await api('/api/admin/stores', { method: 'POST', body: JSON.stringify(body) });
      dialog.close(); $('#createStoreForm').reset(); $('#newMaxDevices').value = '1'; $('#newSubscriptionDays').value = '30';
      $('#tempPasswordResult').textContent = `الحساب ${result.username} أُنشئ. كلمة المرور المؤقتة: ${result.password} — انسخها الآن وأرسلها بأمان.`; $('#tempPasswordResult').classList.remove('hidden');
      notice('تم إنشاء الحساب.'); await loadDashboard(); await openStore(result.id);
    } catch (error) { notice(error.message, true); }
  });
  async function openStore(id) {
    try {
      const result = await api(`/api/admin/stores/${id}`); current = result.store; currentSnapshot = result.snapshot; currentBackups = result.backups || []; currentContactArchives = result.contactArchives || [];
      $('#storePanel').classList.remove('hidden'); $('#storeHeading').textContent = current.name;
      $('#storeMeta').textContent = `${current.username} · أنشئ ${new Date(current.createdAt).toLocaleDateString('ar-PS')}`;
      $('#storeName').value = current.name; $('#storeUsername').value = current.username; $('#debtorLimit').value = current.debtorLimit; $('#maxDevices').value = current.maxDevices || 1;
      $('#verified').checked = current.verified; $('#whatsappEnabled').checked = current.whatsappEnabled;
      $('#permissions').innerHTML = permissionNames.map(name => `<label><input type="checkbox" data-perm="${name}" ${current.permissions[name] !== false ? 'checked' : ''}>${labels[name]}</label>`).join('');
      $('#freezeState').textContent = current.status === 'active' ? 'الحساب فعال حاليًا.' : current.suspendUntil ? `الحساب مجمّد حتى ${dateFmt(current.suspendUntil)}.` : 'الحساب مجمّد حتى يعيد الأدمن تفعيله.';
      $('#subscriptionMode').value = current.subscriptionMode || 'permanent';
      $('#subscriptionState').textContent = current.subscriptionMode === 'permanent' ? 'فعال دائمًا.' : current.subscriptionMode === 'paused' ? `الاشتراك موقوف، والمدة المتبقية محفوظة: ${duration(current.subscriptionRemainingMs)}.` : current.subscriptionRemainingMs > 0 ? `متبقي ${duration(current.subscriptionRemainingMs)}، وينتهي ${dateFmt(current.subscriptionExpiresAt)}.` : `انتهى الاشتراك في ${dateFmt(current.subscriptionExpiresAt)}. يحتاج تجديدًا من الأدمن.`;
      $('#extendSubscription').disabled = current.subscriptionMode === 'permanent';
      renderDevices(result.devices || []); renderAuditEvents(result.auditEvents || []); renderBackups(currentBackups); renderContactArchives(); renderStoreStats(); $('#tempPasswordResult').classList.add('hidden');
      $('#storePanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { notice(error.message, true); }
  }
  function renderDevices(devices) {
    $('#deviceList').innerHTML = devices.length ? devices.map((device,index) => {
      const perms={registerPayments:true,deleteRecords:true,deleteContacts:true,...(device.permissions||{})};
      const safeId=esc(encodeURIComponent(device.id));
      return `<article class="device-card" data-device-row="${safeId}"><div class="record-row"><div><b>${esc(device.staffName || 'صاحب المتجر')}</b><small>${esc(device.label || 'جهاز مسجل')} · آخر دخول: ${esc(dateFmt(device.lastSeenAt))}${device.revokedAt ? ' · أُلغي السماح' : ''}</small></div>${device.revokedAt ? `<button class="button secondary" data-allow="${safeId}">السماح مجددًا</button>` : `<button class="button danger" data-revoke="${safeId}">إيقاف مؤقت</button>`}</div><div class="device-permissions"><label><input type="checkbox" data-device-perm="registerPayments" ${perms.registerPayments?'checked':''}> تسجيل الدفعات</label><label><input type="checkbox" data-device-perm="deleteRecords" ${perms.deleteRecords?'checked':''}> حذف السجلات</label><label><input type="checkbox" data-device-perm="deleteContacts" ${perms.deleteContacts?'checked':''}> حذف الأشخاص</label><button class="button secondary" data-save-device="${safeId}">حفظ الصلاحيات</button><button class="button quiet" data-delete-device="${safeId}">إزالة الاسم والجهاز</button></div></article>`;
    }).join('') : '<p class="muted">لا توجد أجهزة مربوطة بعد.</p>';
    document.querySelectorAll('[data-save-device]').forEach(button => button.addEventListener('click', async () => {
      const deviceId=decodeURIComponent(button.dataset.saveDevice), row=button.closest('[data-device-row]');
      const permissions={}; row.querySelectorAll('[data-device-perm]').forEach(input=>permissions[input.dataset.devicePerm]=input.checked);
      try { await api(`/api/admin/stores/${current.id}/devices/${encodeURIComponent(deviceId)}/permissions`,{method:'POST',body:JSON.stringify(permissions)}); notice('تم حفظ صلاحيات هذا المستخدم.'); }
      catch(error){notice(error.message,true);}
    }));
    document.querySelectorAll('[data-revoke]').forEach(button => button.addEventListener('click', async () => {
      const deviceId = decodeURIComponent(button.dataset.revoke);
      if (!confirm('إلغاء ربط هذا الجهاز؟ سيُطلب من صاحبه التواصل مع الأدمن قبل استخدامه مجددًا.')) return;
      try { await api(`/api/admin/stores/${current.id}/devices/${encodeURIComponent(deviceId)}/revoke`, { method: 'POST' }); notice('تم إلغاء الجهاز.'); await openStore(current.id); }
      catch (error) { notice(error.message, true); }
    }));
    document.querySelectorAll('[data-allow]').forEach(button => button.addEventListener('click', async () => {
      const deviceId = decodeURIComponent(button.dataset.allow);
      try { await api(`/api/admin/stores/${current.id}/devices/${encodeURIComponent(deviceId)}/allow`, { method: 'POST' }); notice('تم السماح للجهاز مجددًا.'); await openStore(current.id); }
      catch (error) { notice(error.message, true); }
    }));
    document.querySelectorAll('[data-delete-device]').forEach(button => button.addEventListener('click', async () => {
      const deviceId=decodeURIComponent(button.dataset.deleteDevice);
      if(!confirm('سيتم حذف ربط اسم هذا المستخدم والجهاز وإنهاء جلسته. عند دخوله لاحقًا سيُسجّل الاسم من جديد. متابعة؟')) return;
      try { await api(`/api/admin/stores/${current.id}/devices/${encodeURIComponent(deviceId)}/delete`,{method:'DELETE'}); notice('تم حذف اسم المستخدم وربط الجهاز.'); await openStore(current.id); }
      catch(error){notice(error.message,true);}
    }));
  }
  function renderAuditEvents(events) {
    const box=$('#deviceAuditList');
    box.innerHTML=events.length?events.map(item=>`<div class="record-row"><div><b>${esc(item.action)} · ${esc(item.actor||'صاحب المتجر')}</b><small>${esc(item.description)} · ${esc(dateFmt(item.createdAt))}</small></div></div>`).join(''):'<p class="muted">لا توجد حركات مسجلة بعد.</p>';
  }
  function renderBackups(backups) {
    const select = $('#backupSelect'); select.innerHTML = backups.length ? backups.map(item => `<option value="${item.id}">${esc(dateFmt(item.createdAt))} · ${esc(item.reason)} · ${fmt(item.contactCount)} أشخاص / ${fmt(item.debtCount)} ديون / ${fmt(item.paymentCount)} دفعات</option>`).join('') : '<option value="">لا توجد نسخ محفوظة</option>';
    $('#restoreContact').innerHTML = '<option value="">اختر النسخة أولًا</option>'; $('#restoreInfo').textContent = backups.length ? `عدد النسخ المحفوظة: ${backups.length} (آخر 100 نسخة).` : 'ستُنشأ النسخة الأولى بعد أول مزامنة بيانات.';
  }
  $('#backupSelect').addEventListener('change', async () => {
    const backupId = $('#backupSelect').value; $('#restoreContact').innerHTML = '<option value="">جارٍ تحميل الأشخاص…</option>';
    if (!backupId || !current) { $('#restoreContact').innerHTML = '<option value="">اختر النسخة أولًا</option>'; return; }
    try {
      const result = await api(`/api/admin/stores/${current.id}/backups/${backupId}`); const contacts = result.snapshot.contacts || [];
      $('#restoreContact').innerHTML = contacts.length ? `<option value="">اختر شخصًا للاستعادة</option>${contacts.map(person => `<option value="${person.id}">${esc(person.name)}${person.phone ? ` · ${esc(person.phone)}` : ''}</option>`).join('')}` : '<option value="">النسخة لا تحتوي أشخاصًا</option>';
      $('#restoreInfo').textContent = `${result.backup.reason} · ${dateFmt(result.backup.createdAt)} · يمكن استعادة المتجر كاملًا أو شخص واحد.`;
    } catch (error) { $('#restoreContact').innerHTML = '<option value="">تعذر تحميل النسخة</option>'; notice(error.message, true); }
  });
  async function restoreBackup(contactId = null) {
    const backupId = $('#backupSelect').value;
    if (!backupId) { notice('اختر نسخة محفوظة أولًا.', true); return; }
    const message = contactId == null ? 'سيستبدل هذا سجل المتجر الحالي بمحتوى النسخة المختارة. متابعة؟' : 'سيستبدل سجل هذا الشخص فقط بمحتوى النسخة المختارة. متابعة؟';
    if (!confirm(message)) return;
    try {
      await api(`/api/admin/stores/${current.id}/backups/${backupId}/restore`, { method: 'POST', body: JSON.stringify({ contactId }) });
      notice(contactId == null ? 'تمت استعادة سجل المتجر.' : 'تمت استعادة سجل الشخص.'); await loadDashboard(); await openStore(current.id);
    } catch (error) { notice(error.message, true); }
  }
  $('#restoreStore').addEventListener('click', () => restoreBackup());
  $('#restorePerson').addEventListener('click', () => { const value = $('#restoreContact').value; if (!value) { notice('اختر شخصًا من النسخة أولًا.', true); return; } restoreBackup(Number(value)); });
  function renderContactArchives() {
    const box = $('#deletedContactArchives');
    if (!currentContactArchives.length) { box.innerHTML = '<p class="muted">لا توجد سجلات زبائن محذوفين محفوظة.</p>'; return; }
    box.innerHTML = currentContactArchives.map(archive => `<div class="record-row"><div><b>${esc(archive.contactName)}</b><small>حُفظ ${esc(dateFmt(archive.archivedAt))} · ${fmt(archive.debtCount)} دين · ${fmt(archive.paymentCount)} دفعة${archive.restoredAt ? ' · تمت الاستعادة سابقًا' : ''}</small></div><button class="button ${archive.restoredAt ? 'quiet' : 'secondary'}" data-restore-archive="${Number(archive.id)}" ${archive.restoredAt ? 'disabled' : ''}>${archive.restoredAt ? 'تمت الاستعادة' : 'استعادة للمتجر'}</button></div>`).join('');
    box.querySelectorAll('[data-restore-archive]').forEach(button => button.addEventListener('click', async () => {
      const archive = currentContactArchives.find(item => Number(item.id) === Number(button.dataset.restoreArchive));
      if (!archive || archive.restoredAt || !current) return;
      if (!confirm(`استعادة ${archive.contactName} وسجل حركاته إلى حساب المتجر؟`)) return;
      const storeId = current.id;
      try {
        await api(`/api/admin/stores/${storeId}/archives/${archive.id}/restore`, { method: 'POST', body: '{}' });
        notice(`تمت استعادة ${archive.contactName} إلى حساب المتجر.`); await loadDashboard(); await openStore(storeId);
      } catch (error) { notice(error.message, true); }
    }));
  }
  $('#revokeAllDevices').addEventListener('click', async () => {
    if (!current || !confirm('سيتم فصل كل الأجهزة الحالية. يجب أن يسجل الدخول بها مجددًا بعد ذلك. متابعة؟')) return;
    try { await api(`/api/admin/stores/${current.id}/devices/revoke-all`, { method: 'POST' }); notice('تم إلغاء كل الأجهزة المسجلة.'); await openStore(current.id); }
    catch (error) { notice(error.message, true); }
  });
  function renderStoreStats() {
    if (!currentSnapshot) return;
    const active = currentSnapshot.debts.filter(debt => debt.remaining > 0).length, totals = currentSnapshot.totals;
    $('#storeStats').innerHTML = [['الأشخاص', currentSnapshot.contacts.length], ['الديون المفتوحة', active], ['مستحق للمتجر', `${fmt(totals.receivable)} ₪`], ['مستحق على المتجر', `${fmt(totals.payable)} ₪`], ['الدفعات', currentSnapshot.payments.length], ['صافي الرصيد', `${fmt(totals.net)} ₪`]].map(item => `<div class="mini-metric"><span>${item[0]}</span><b>${item[1]}</b></div>`).join('');
  }
  $('#closeStore').addEventListener('click', () => $('#storePanel').classList.add('hidden'));
  $('#storeSettings').addEventListener('submit', async event => {
    event.preventDefault();
    try {
      const maxDevices = integerInput('#maxDevices', 1, 50, current?.maxDevices || 1);
      const debtorLimit = integerInput('#debtorLimit', 0, 1000000, 0);
      const permissions = {}; for (const name of permissionNames) permissions[name] = $(`[data-perm="${name}"]`).checked;
      const result = await api(`/api/admin/stores/${current.id}/update`, { method: 'POST', body: JSON.stringify({ name: $('#storeName').value.trim(), username: $('#storeUsername').value.trim(), debtorLimit, maxDevices, verified: $('#verified').checked, whatsappEnabled: $('#whatsappEnabled').checked, permissions }) });
      const savedLimit = Number(result.store?.maxDevices ?? result.store?.allowedDevices);
      if (savedLimit !== maxDevices) throw new Error('لم يؤكد الخادم حفظ عدد الأجهزة المطلوب. أعد المحاولة.');
      notice(`تم حفظ الإعدادات. عدد الأجهزة المسموحة الآن: ${savedLimit}.`); await loadDashboard(); await openStore(current.id);
    } catch (error) { notice(error.message, true); }
  });
  async function changeStatus(status, until = null) {
    if (!current) return;
    try { await api(`/api/admin/stores/${current.id}/update`, { method: 'POST', body: JSON.stringify({ status, suspendUntil: until }) }); notice(status === 'active' ? 'تم تفعيل الحساب.' : 'تم تجميد الحساب.'); await loadDashboard(); await openStore(current.id); }
    catch (error) { notice(error.message, true); }
  }
  $('#activateStore').addEventListener('click', () => changeStatus('active'));
  $('#freezeHour').addEventListener('click', () => changeStatus('suspended', Date.now() + 3600000));
  $('#freezeDay').addEventListener('click', () => changeStatus('suspended', Date.now() + 86400000));
  $('#freezeForever').addEventListener('click', () => changeStatus('suspended', null));
  $('#saveSubscription').addEventListener('click', async () => {
    try {
      const mode = $('#subscriptionMode').value, body = { mode };
      if (mode === 'timed') body.days = Number($('#subscriptionDays').value);
      await api(`/api/admin/stores/${current.id}/subscription`, { method: 'POST', body: JSON.stringify(body) });
      notice(mode === 'paused' ? 'تم إيقاف الاشتراك.' : 'تم تحديث مدة الاشتراك.'); await loadDashboard(); await openStore(current.id);
    } catch (error) { notice(error.message, true); }
  });
  $('#extendSubscription').addEventListener('click', async () => {
    try {
      const days = Number($('#extendDays').value); if (!Number.isInteger(days) || days < 1) { notice('أدخل عدد أيام صالحًا.', true); return; }
      await api(`/api/admin/stores/${current.id}/subscription`, { method: 'POST', body: JSON.stringify({ mode: 'timed', days, extend: true }) });
      notice('تم تمديد الاشتراك.'); await loadDashboard(); await openStore(current.id);
    } catch (error) { notice(error.message, true); }
  });
  $('#resetPassword').addEventListener('click', async () => {
    try {
      const password = $('#newTempPassword').value || generatePassword(); const result = await api(`/api/admin/stores/${current.id}/reset-password`, { method: 'POST', body: JSON.stringify({ password }) });
      $('#newTempPassword').value = ''; $('#tempPasswordResult').textContent = `المستخدم: ${result.username} · كلمة المرور المؤقتة: ${result.password} · أُخرجت الجلسات الحالية ويجب تغييرها عند الدخول.`;
      $('#tempPasswordResult').classList.remove('hidden'); notice('تم تعيين كلمة مرور مؤقتة.');
    } catch (error) { notice(error.message, true); }
  });
  $('#deleteStore').addEventListener('click', async () => {
    if (!current) return; const confirmName = prompt(`هذا يحذف الحساب وكل بياناته ونسخه الاحتياطية نهائيًا. اكتب اسم المستخدم (${current.username}) للتأكيد:`);
    if (confirmName !== current.username) return;
    try { await api(`/api/admin/stores/${current.id}/permanent?confirm=${encodeURIComponent(confirmName)}`, { method: 'DELETE' }); $('#storePanel').classList.add('hidden'); current = null; notice('تم الحذف النهائي.'); await loadDashboard(); }
    catch (error) { notice(error.message, true); }
  });
  $('#downloadReport').addEventListener('click', () => {
    if (!currentSnapshot) return;
    const rows = [['النوع', 'الشخص', 'الاتجاه', 'المبلغ', 'التاريخ', 'البيان'], ...currentSnapshot.transactions.map(item => [item.kind === 'payment' ? 'دفعة' : 'دين', item.contactName, item.direction, item.amount, new Date(item.createdAt).toLocaleDateString('ar-PS'), item.note || ''])];
    const csv = '\ufeff' + rows.map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const anchor = document.createElement('a'); anchor.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })); anchor.download = `sadad-store-${current.id}.csv`; anchor.click(); URL.revokeObjectURL(anchor.href);
  });
  startup();
})();
