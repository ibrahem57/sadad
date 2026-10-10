(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const tokenKey = 'sadad-admin-session';
  const apiBase = String(window.SADAD_API_BASE_URL || '').replace(/\/+$/, '');
  const publishableKey = String(window.SADAD_SUPABASE_PUBLISHABLE_KEY || '').trim();
  let token = sessionStorage.getItem(tokenKey) || '';
  let stores = [];
  let current = null;
  let currentSnapshot = null;
  let currentBackups = [];
  let currentContactArchives = [];
  let bootstrap = false;
  let currentInstallations = [];
  let storeRequest = 0;
  let pendingAction = null;

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
    if (publishableKey) headers.apikey = publishableKey;
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(`${apiBase}${path}`, { ...options, headers });
    const data = await response.json().catch(() => ({ ok: false, message: 'استجابة الخادم غير مفهومة.' }));
    if (!response.ok || data.ok === false) {
      if (response.status === 401 && token) { sessionStorage.removeItem(tokenKey); token = ''; showAuth(false); }
      throw new Error(data.message || 'تعذر تنفيذ العملية.');
    }
    return data;
  }
  function showAuth(setup, passwordChange = false) {
    ++storeRequest; current=null; currentSnapshot=null; $('#authPassword').value=''; $('#storePanel').classList.add('hidden'); $('#ledgerPanel').classList.add('hidden'); $('#tempPasswordResult').textContent='';
    bootstrap = setup; $('#authView').classList.remove('hidden'); $('#appView').classList.add('hidden');
    $('#authForm').classList.toggle('hidden', passwordChange); $('#adminPasswordChangeForm').classList.toggle('hidden', !passwordChange);
    $('#setupKeyWrap').classList.toggle('hidden', !setup); $('#authTitle').textContent = passwordChange ? 'غيّر كلمة مرور الأدمن' : setup ? 'إنشاء مدير النظام' : 'دخول الأدمن';
    $('#authHint').textContent = passwordChange ? 'عيّن كلمة مرور جديدة من 12 خانة أو أكثر للمتابعة.' : setup ? 'أنشئ حساب الأدمن الأول. مفتاح التأسيس موجود في ملف البيئة على الخادم.' : 'هذه اللوحة خاصة بمدير النظام.';
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
    $('#connectionState').textContent = 'جارٍ تحديث البيانات…';
    try {
      const result = await api(`/api/admin/dashboard?includeArchived=${$('#showArchived').checked}`);
      $('#adminName').textContent = result.meta.username; stores = result.stores;
      $('#metrics').innerHTML = [['المتاجر', result.overview.stores], ['النشطة', result.overview.active], ['الموثقة', result.overview.trusted], ['أصل الديون الحالي', `${fmt(result.overview.debts)} ₪`]].map(item => `<article class="metric"><span>${item[0]}</span><b>${item[1]}</b></article>`).join('');
      $('#connectionState').textContent = `متصل بقاعدة البيانات · آخر تحديث ${new Date().toLocaleTimeString('ar-PS')} · طلبات هواتف بانتظار الاعتماد: ${fmt(result.overview.pendingDevices)}`;
      renderStores();
    } catch (error) { $('#connectionState').textContent = 'تعذر تحديث البيانات. اضغط تحديث للمحاولة مجددًا.'; throw error; }
  }
  $('#refreshDashboard').addEventListener('click', async () => { try { await loadDashboard(); if (current) await openStore(current.id); } catch(error) {notice(error.message,true);} });
  $('#showArchived').addEventListener('change', () => loadDashboard().catch(error => notice(error.message,true)));

  function renderStores() {
    const query = $('#searchStores').value.trim().toLowerCase();
    const rows = stores.filter(store => `${store.name} ${store.username}`.toLowerCase().includes(query));
    $('#storesBody').innerHTML = rows.map(store => {
      const subscription = store.subscriptionMode === 'permanent' ? 'فعال دائمًا' : store.subscriptionMode === 'paused' ? 'موقوف' : store.subscriptionRemainingMs > 0 ? `متبقي ${duration(store.subscriptionRemainingMs)}` : 'منتهي';
      return `<tr><td><b>${esc(store.name)}</b>${store.verified ? '<small title="حساب موثّق" aria-label="حساب موثّق" style="display:inline-grid;place-items:center;width:17px;height:17px;margin-inline-start:7px;border-radius:50%;background:#d9f3ec;color:#087765;font-size:11px;font-weight:800;vertical-align:middle">✓</small>' : ''}</td><td>${esc(store.username)}</td><td><span class="status ${store.status === 'active' ? '' : 'suspended'}">${store.archived ? 'مؤرشف' : store.status === 'active' ? 'نشط' : 'مجمّد'}</span></td><td>${esc(subscription)}</td><td>${store.boundDevices ? 'معتمد' : 'غير معتمد'}${store.pendingDevices ? `<small class="pending-device">${fmt(store.pendingDevices)} طلب انتظار</small>` : ''}</td><td>${store.verified ? '<b class="verified">✓ موثوق</b>' : '—'}</td><td>${fmt(store.contacts)}</td><td>${fmt(store.debts)} · ${fmt(store.debtTotal)} ₪</td><td><button class="button quiet" data-open="${store.id}">إدارة</button></td></tr>`;
    }).join('');
    $('#emptyStores').classList.toggle('hidden', rows.length > 0);
    document.querySelectorAll('[data-open]').forEach(button => button.addEventListener('click', () => openStore(Number(button.dataset.open))));
  }
  $('#searchStores').addEventListener('input', renderStores);
  const dialog = $('#createDialog');
  $('#newStoreBtn').addEventListener('click', () => { $('#createError').textContent=''; dialog.showModal(); }); $('#cancelCreate').addEventListener('click', () => dialog.close());
  function generatePassword() { const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%'; const bytes = new Uint32Array(18); crypto.getRandomValues(bytes); return Array.from(bytes, value => chars[value % chars.length]).join(''); }
  $('#generatePassword').addEventListener('click', () => $('#newPassword').value = generatePassword());
  $('#newSubscriptionMode').addEventListener('change', () => { $('#newSubscriptionDaysWrap').classList.toggle('hidden', $('#newSubscriptionMode').value !== 'timed'); });
  $('#createStoreForm').addEventListener('submit', async event => {
    event.preventDefault(); $('#createError').textContent='';
    const submit=$('#createStoreForm button.primary'); if(submit.disabled)return; submit.disabled=true;
    try {
      const body = { name: $('#newName').value.trim(), username: $('#newUsername').value.trim(), password: $('#newPassword').value, debtorLimit: integerInput('#newLimit', 0, 1000000, 0), maxDevices: integerInput('#newMaxDevices', 1, 50, 1), subscriptionMode: $('#newSubscriptionMode').value, subscriptionDays: integerInput('#newSubscriptionDays', 1, 36500, 30) };
      const result = await api('/api/admin/stores', { method: 'POST', body: JSON.stringify(body) });
      dialog.close(); $('#createStoreForm').reset(); $('#newMaxDevices').value = '1'; $('#newSubscriptionDays').value = '30'; $('#newSubscriptionDaysWrap').classList.remove('hidden');
      await loadDashboard(); await openStore(result.id);
      $('#tempPasswordResult').textContent = `الحساب ${result.username} أُنشئ. كلمة المرور المؤقتة: ${result.password} — انسخها الآن وأرسلها بأمان.`; $('#tempPasswordResult').classList.remove('hidden');
      notice('تم إنشاء الحساب. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور ثم تطلب موافقتك على الهاتف.');
    } catch (error) { $('#createError').textContent=error.message; notice(error.message, true); }
    finally {submit.disabled=false;}
  });
  async function openStore(id) {
    const request = ++storeRequest;
    current=null; currentSnapshot=null; $('#ledgerPanel').classList.add('hidden');
    $('#storePanel').classList.add('hidden');
    try {
      const result = await api(`/api/admin/stores/${id}`);
      if(request!==storeRequest)return;
      current = result.store; currentSnapshot = result.snapshot; currentBackups = result.backups || []; currentContactArchives = result.contactArchives || [];
      $('#ledgerSearch').value=''; $('#storePanel').classList.remove('hidden'); $('#storeHeading').textContent = current.name;
      $('#storeMeta').textContent = `${current.username} · أنشئ ${new Date(current.createdAt).toLocaleDateString('ar-PS')}`;
      $('#storeName').value = current.name; $('#storeUsername').value = current.username; $('#debtorLimit').value = current.debtorLimit; $('#maxDevices').value = current.maxDevices || 1;
      $('#verified').checked = current.verified; $('#whatsappEnabled').checked = current.whatsappEnabled;
      $('#permissions').innerHTML = permissionNames.map(name => `<label><input type="checkbox" data-perm="${name}" ${current.permissions[name] !== false ? 'checked' : ''}>${labels[name]}</label>`).join('');
      $('#freezeState').textContent = current.status === 'active' ? 'الحساب فعال حاليًا.' : current.suspendUntil ? `الحساب مجمّد حتى ${dateFmt(current.suspendUntil)}.` : 'الحساب مجمّد حتى يعيد الأدمن تفعيله.';
      $('#subscriptionMode').value = current.subscriptionMode || 'permanent';
      $('#subscriptionState').textContent = current.subscriptionMode === 'permanent' ? 'فعال دائمًا.' : current.subscriptionMode === 'paused' ? `الاشتراك موقوف، والمدة المتبقية محفوظة: ${duration(current.subscriptionRemainingMs)}.` : current.subscriptionRemainingMs > 0 ? `متبقي ${duration(current.subscriptionRemainingMs)}، وينتهي ${dateFmt(current.subscriptionExpiresAt)}.` : `انتهى الاشتراك في ${dateFmt(current.subscriptionExpiresAt)}. يحتاج تجديدًا من الأدمن.`;
      $('#extendSubscription').disabled = current.subscriptionMode === 'permanent';
      currentInstallations=result.installations || [];
      renderInstallations(); renderDevices(result.devices || []); renderControlHistory(result); renderBackups(currentBackups); renderContactArchives(); renderStoreStats(); renderLedger(); renderRecovery(result.recoveryPermits || []);
      const modern=current.ledgerMode==='current';
      $('#legacyBackups').classList.toggle('hidden',modern || current.archived); $('#legacyArchives').classList.toggle('hidden',modern || current.archived);
      $('#storeSettingsFields').disabled=current.archived; $('#recoveryFields').disabled=current.archived;
      ['activateStore','freezeHour','freezeDay','freezeForever','resetPassword','saveSubscription','extendSubscription','endStoreSessions','revokeAllDevices'].forEach(id=>$('#'+id).disabled=current.archived || (id==='extendSubscription' && current.subscriptionMode==='permanent'));
      $('#archiveStore').classList.toggle('hidden',current.archived); $('#unarchiveStore').classList.toggle('hidden',!current.archived);
      $('#deleteStore').classList.toggle('hidden',current.archived || !current.canDelete);
      $('#archiveHint').textContent=current.archived ? 'الحساب مؤرشف. إعادته تحفظ الدفتر وتتركه موقوفًا حتى تفعّله وتعتمد هاتفه.' : 'الأرشفة توقف الحساب وأجهزته وتحفظ دفتره وسجل نشاطه.';
      $('#ledgerReadInfo').textContent=currentSnapshot.requestId ? `قراءة مسجلة · رقم الطلب: ${currentSnapshot.requestId}` : 'لم يُهيأ الدفتر المعتمد بعد؛ تظهر بيانات السجل السابق إن وجدت.';
      $('#tempPasswordResult').textContent=''; $('#tempPasswordResult').classList.add('hidden');
      $('#storePanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { notice(error.message, true); }
  }
  function renderDevices(devices) {
    if(current.ledgerMode==='current' || current.archived) {
      $('#deviceList').innerHTML=devices.length ? devices.map(device=>`<div class="record-row"><div><b>${esc(device.staffName || device.label || 'جهاز تسجيل دخول')}</b><small dir="ltr">${esc(device.id)}</small><small>${esc(dateFmt(device.lastSeenAt))} · ${device.revokedAt?'منتهٍ':'مسجل'}</small></div></div>`).join('') : '<p class="muted">لا توجد أجهزة تسجيل دخول.</p>';
      return;
    }
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
    $('#restoreContact').innerHTML = '<option value="">اختر النسخة أولًا</option>'; $('#restoreInfo').textContent = backups.length ? `عدد النسخ المحفوظة: ${backups.length} (آخر 7 نسخ قبل الحذف).` : 'تُحفظ نسخة قبل حذف البيانات، ونسخة كاملة لقاعدة البيانات يوميًا على الخادم.';
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
  $('#revokeAllDevices').addEventListener('click', () => accountAction('revoke-all','إلغاء اعتماد جميع الأجهزة','تُوقف صلاحية جميع الهواتف للمزامنة وتُنهي جلسات الدخول. سيحتاج الهاتف إلى تسجيل جديد واعتمادك.'));
  $('#endStoreSessions').addEventListener('click', () => accountAction('end-sessions','إنهاء جلسات الدخول','تُنهي جلسات المتجر وتفرغ أماكن تسجيل الدخول. اعتماد الهاتف يبقى محفوظًا ويمكنه تسجيل الدخول مجددًا.'));
  $('#archiveStore').addEventListener('click', () => accountAction('archive','أرشفة الحساب','يُوقف الحساب وتُلغى صلاحية أجهزته. يبقى الدفتر والتاريخ محفوظين ويمكن إعادة الحساب لاحقًا.'));
  $('#unarchiveStore').addEventListener('click', () => accountAction('unarchive','إعادة الحساب من الأرشيف','يعود الحساب موقوفًا مع دفتره المحفوظ. فعّل الاشتراك والحساب ثم اعتمد هاتفًا جديدًا.'));
  function renderStoreStats() {
    if (!currentSnapshot) return;
    const active = currentSnapshot.debts.filter(debt => debt.remaining > 0).length, totals = currentSnapshot.totals;
    $('#storeStats').innerHTML = [['الأشخاص', currentSnapshot.contacts.length], ['الديون المفتوحة', active], ['مستحق للمتجر', `${fmt(totals.receivable)} ₪`], ['مستحق على المتجر', `${fmt(totals.payable)} ₪`], ['الدفعات', currentSnapshot.payments.length], ['صافي الرصيد', `${fmt(totals.net)} ₪`]].map(item => `<div class="mini-metric"><span>${item[0]}</span><b>${item[1]}</b></div>`).join('');
  }
  $('#closeStore').addEventListener('click', () => { ++storeRequest; current=null; currentSnapshot=null; $('#storePanel').classList.add('hidden'); $('#ledgerPanel').classList.add('hidden'); });
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
      const password = $('#newTempPassword').value || generatePassword(); if(password.length<12 || password.length>128)throw new Error('كلمة المرور المؤقتة يجب أن تكون بين 12 و128 خانة.'); const result = await api(`/api/admin/stores/${current.id}/reset-password`, { method: 'POST', body: JSON.stringify({ password }) });
      $('#newTempPassword').value = ''; $('#tempPasswordResult').textContent = `المستخدم: ${result.username} · كلمة المرور المؤقتة: ${result.password} · أُخرجت الجلسات الحالية ويجب تغييرها عند الدخول.`;
      $('#tempPasswordResult').classList.remove('hidden'); notice('تم تعيين كلمة مرور مؤقتة.');
    } catch (error) { notice(error.message, true); }
  });
  $('#deleteStore').addEventListener('click', async () => {
    if (!current) return; const confirmName = prompt(`حذف حساب لم يُستخدم في الدفتر المعتمد نهائيًا. اكتب اسم المستخدم (${current.username}) للتأكيد:`);
    if (confirmName !== current.username) return;
    try { await api(`/api/admin/stores/${current.id}/permanent?confirm=${encodeURIComponent(confirmName)}`, { method: 'DELETE' }); $('#storePanel').classList.add('hidden'); current = null; $('#ledgerPanel').classList.add('hidden'); notice('تم الحذف النهائي.'); await loadDashboard(); }
    catch (error) { notice(error.message, true); }
  });
  $('#downloadReport').addEventListener('click', () => {
    if (!currentSnapshot) return;
    const rows = [['النوع', 'الشخص', 'الاتجاه', 'المبلغ', 'التاريخ', 'البيان'], ...currentSnapshot.transactions.map(item => [transactionName(item.kind), item.contactName, item.direction, item.amount, new Date(item.createdAt).toLocaleDateString('ar-PS'), item.note || ''])];
    const csv = '\ufeff' + rows.map(row => row.map(value => `"${(typeof value==='string' && /^[=+@\t\r-]/.test(value)?"\'"+value:String(value)).replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const anchor = document.createElement('a'); anchor.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })); anchor.download = `sadad-store-${current.id}.csv`; anchor.click(); URL.revokeObjectURL(anchor.href);
  });
  const transactionName = kind => ({debt:'دين',payment:'دفعة',correction:'تصحيح دين',reversal:'عكس دفعة'}[kind] || kind);
  const directionName = direction => direction==='receivable' ? 'للـمتجر' : direction==='payable' ? 'على المتجر' : '—';
  const actionName = action => ({'contact.create':'إضافة شخص','contact.archive':'أرشفة شخص','contact.unarchive':'إعادة شخص','debt.create':'إضافة دين','debt.correct':'تصحيح دين','payment.create':'تسجيل دفعة','payment.reverse':'عكس دفعة','operation.rejected':'عملية مرفوضة','installation.approved':'اعتماد هاتف','installation.revoked':'إلغاء اعتماد هاتف','recovery.approved':'اعتماد استرداد','account.archive':'أرشفة حساب','account.unarchive':'إعادة حساب من الأرشيف','account.end-sessions':'إنهاء الجلسات','account.revoke-all':'إلغاء جميع الأجهزة'}[action] || action);
  const isoDate = value => value ? new Date(value).toLocaleString('ar-PS') : '—';
  async function ledgerApi(path, body) {
    const ledgerBase=apiBase.replace(/\/sadad-api$/, '/sadid-ledger');
    const response=await fetch(`${ledgerBase}/api/admin${path}`, {method:body===undefined?'GET':'POST',headers:{apikey:publishableKey,'Content-Type':'application/json','X-Sadad-Admin':token},body:body===undefined?undefined:JSON.stringify(body)});
    const data=await response.json().catch(()=>({ok:false,message:'تعذر قراءة رد الخادم.'}));
    if(!response.ok || data.ok===false) {
      if(response.status===401 || data.code==='admin_authorization_required'){token='';sessionStorage.removeItem(tokenKey);showAuth(false);}
      throw new Error(data.message || 'تعذر تنفيذ الإجراء.');
    }
    return data;
  }
  function renderInstallations() {
    $('#installationList').innerHTML=currentInstallations.length ? currentInstallations.map(phone=>`<article class="device-card"><div class="record-row"><div><b class="installation-state ${esc(phone.status)}">${esc({pending:'بانتظار اعتمادك',active:'هاتف معتمد',revoked:'أُلغي الاعتماد'}[phone.status])} · ${fmt(phone.generation)}</b><code dir="ltr">${esc(phone.installationId)}</code><small>سُجل ${esc(isoDate(phone.createdAt))}${phone.approvedBy?` · اعتمده ${esc(phone.approvedBy)}`:''}</small>${phone.reason?`<small>${esc(phone.reason)}</small>`:''}</div><div class="button-row">${phone.status==='pending'?`<button class="button primary" data-installation-approve="${esc(phone.installationId)}" ${current.archived?'disabled':''}>اعتماد الهاتف</button>`:''}${phone.status!=='revoked'?`<button class="button danger" data-installation-revoke="${esc(phone.installationId)}" ${current.archived?'disabled':''}>إلغاء الاعتماد</button>`:''}</div></div>${phone.status==='revoked'?'<p class="small muted">لإعادة هذا الهاتف، سجله كجهاز جديد من التطبيق ثم اعتمد طلبه الجديد.</p>':''}</article>`).join('') : '<p class="muted">لا توجد طلبات هواتف. يسجل صاحب المتجر الدخول ويغيّر كلمة المرور المؤقتة ثم يظهر طلب هاتفه هنا.</p>';
    $('#installationList').querySelectorAll('[data-installation-approve]').forEach(button=>button.addEventListener('click',()=>installationAction(button.dataset.installationApprove,'approve')));
    $('#installationList').querySelectorAll('[data-installation-revoke]').forEach(button=>button.addEventListener('click',()=>installationAction(button.dataset.installationRevoke,'revoke')));
  }
  function beginAction(title,description,run) {
    $('#actionForm').reset(); $('#actionTitle').textContent=title; $('#actionDescription').textContent=description; $('#actionError').textContent='';
    pendingAction=run; $('#actionDialog').showModal();
  }
  function installationAction(installationId,action) {
    const storeId=current.id;
    const title=action==='approve'?'اعتماد هاتف المتجر':'إلغاء اعتماد الهاتف';
    const description=action==='approve'?'سينقل هذا الاعتماد صلاحية المزامنة إلى الهاتف المختار ويلغي اعتماد الهاتف السابق. تأكد من حفظ إدخالات الهاتف السابق قبل النقل.':'تتوقف صلاحية هذا الهاتف للمزامنة فورًا، وتبقى البيانات التي حُفظت عليه سابقًا وفق سياسة التطبيق.';
    beginAction(title,description,async reason=>{await ledgerApi(`/installations/${action}`,{installationId,reason});return storeId;});
  }
  function accountAction(action,title,description) {
    if(!current)return;
    const storeId=current.id;
    beginAction(title,description,async reason=>{await api(`/api/admin/stores/${storeId}/${action}`,{method:'POST',body:JSON.stringify({reason})});return storeId;});
  }
  $('#cancelAction').addEventListener('click',()=>$('#actionDialog').close());
  $('#actionDialog').addEventListener('close',()=>{pendingAction=null;});
  $('#actionForm').addEventListener('submit',async event=>{
    event.preventDefault(); if(!pendingAction)return;
    const run=pendingAction,reason=$('#actionReason').value.trim();
    if(!reason){$('#actionError').textContent='اكتب سبب الإجراء.';return;}
    $('#confirmAction').disabled=true; $('#actionError').textContent='';
    try{const storeId=await run(reason);$('#actionDialog').close();await loadDashboard();await openStore(storeId);notice('تم حفظ الإجراء وتسجيل سببه.');}
    catch(error){$('#actionError').textContent=error.message;}
    finally{$('#confirmAction').disabled=false;}
  });
  function renderRecovery(permits) {
    $('#recoveryForm').reset();$('#recoveryError').textContent='';$('#recoveryResult').textContent='';$('#recoveryResult').classList.add('hidden');
    const active=currentInstallations.find(phone=>phone.status==='active');
    const old=currentInstallations.filter(phone=>phone.status==='revoked' && active && phone.generation<active.generation);
    $('#recoveryOld').innerHTML='<option value="">اختر الهاتف السابق</option>'+old.map(phone=>`<option value="${esc(phone.installationId)}">${fmt(phone.generation)} · ${esc(phone.installationId)}</option>`).join('');
    $('#recoveryNew').innerHTML=active?`<option value="${esc(active.installationId)}">${fmt(active.generation)} · ${esc(active.installationId)}</option>`:'<option value="">اعتمد الهاتف الجديد أولًا</option>';
    $('#recoveryForm button').disabled=!active || !old.length || current.archived;
    $('#recoveryHistory').innerHTML=permits.length?'<h4>تصاريح الاسترداد السابقة</h4>'+permits.map(permit=>`<div class="record-row"><div><b>${esc(permit.approved_by)} · ${new Date(permit.expires_at)>new Date()?'صالح':'منتهي'}</b><code dir="ltr">${esc(permit.id)}</code><small>${esc(permit.reason)} · ينتهي ${esc(isoDate(permit.expires_at))}</small></div></div>`).join(''):'';
  }
  $('#recoveryFile').addEventListener('change',async()=>{
    const file=$('#recoveryFile').files[0];if(!file)return;
    if(file.size>4000000){$('#recoveryError').textContent='ملف القائمة أكبر من الحد المسموح (4 MB).';return;}
    try{$('#recoveryManifest').value=await file.text();$('#recoveryError').textContent='';}catch(error){$('#recoveryError').textContent='تعذر قراءة الملف.';}
  });
  $('#recoveryForm').addEventListener('submit',async event=>{
    event.preventDefault();const button=$('#recoveryForm button');$('#recoveryError').textContent='';
    const storeId=current.id;
    try{
      const raw=$('#recoveryManifest').value;
      if(new TextEncoder().encode(raw).length>4000000)throw new Error('قائمة الاسترداد أكبر من الحد المسموح.');
      let parsed;try{parsed=JSON.parse(raw);}catch{throw new Error('أدخل قائمة أوامر JSON صالحة من شاشة الاسترداد في الهاتف.');}
      const commands=Array.isArray(parsed)?parsed:parsed?.commands;
      if(!Array.isArray(commands) || commands.length<1 || commands.length>1000)throw new Error('القائمة يجب أن تحتوي من أمر واحد إلى 1000 أمر.');
      if(!$('#recoveryOld').value || !$('#recoveryNew').value)throw new Error('اختر الهاتف السابق والهاتف المعتمد.');
      if(!confirm(`اعتماد استرداد ${commands.length} أمرًا من الهاتف السابق؟ الموافقة تشمل هذه الأوامر الأصلية فقط.`))return;
      button.disabled=true;
      const r=await ledgerApi('/recovery/approve',{oldInstallationId:$('#recoveryOld').value,newInstallationId:$('#recoveryNew').value,commands,reason:$('#recoveryReason').value.trim()});
      await openStore(storeId);
      $('#recoveryResult').innerHTML=`<b>تم اعتماد الاسترداد</b><span class="permit-id">${esc(r.result.permitId)}</span><p>ينتهي ${esc(isoDate(r.result.expiresAt))}. انسخ رقم التصريح وضعه في شاشة استرداد الهاتف الجديد.</p>`;
      $('#recoveryResult').classList.remove('hidden');notice('سُجلت موافقة الاسترداد.');
    }catch(error){$('#recoveryError').textContent=error.message;}
    finally{if(current?.id===storeId)button.disabled=current.archived || !currentInstallations.some(phone=>phone.status==='active') || !currentInstallations.some(phone=>phone.status==='revoked');}
  });
  function renderControlHistory(result) {
    const events=[...(result.auditEvents || []),...(result.controlEvents || []).map(item=>({action:item.kind,actor:item.actor,description:item.reason,createdAt:Date.parse(item.created_at)}))].sort((a,b)=>b.createdAt-a.createdAt).slice(0,200);
    renderAuditEvents(events.map(item=>({...item,action:actionName(item.action),actor:item.deviceId?`هاتف ${String(item.deviceId).slice(0,8)}`:item.actor})));
    if(result.accessEvents?.length)$('#deviceAuditList').insertAdjacentHTML('beforeend',`<details><summary>قراءات الإدارة المسجلة</summary>${result.accessEvents.map(item=>`<div class="record-row"><div><b>${esc(item.actor)}</b><small>${esc(item.reason)} · ${esc(isoDate(item.created_at))}</small><code dir="ltr">${esc(item.id)}</code></div></div>`).join('')}</details>`);
  }
  function renderLedger() {
    if(!currentSnapshot || !current)return;
    $('#ledgerPanel').classList.remove('hidden');
    const query=$('#ledgerSearch').value.trim().toLowerCase();
    const contacts=currentSnapshot.contacts.filter(row=>`${row.name} ${row.phone || ''}`.toLowerCase().includes(query));
    const ids=new Set(contacts.map(row=>String(row.id)));
    $('#ledgerContacts').innerHTML=contacts.length?contacts.map(row=>`<tr><td>${esc(row.name)}</td><td dir="ltr">${esc(row.phone)}</td><td>${row.archived?'مؤرشف':'نشط'}</td><td>${fmt(row.receivable)} ₪</td><td>${fmt(row.payable)} ₪</td></tr>`).join(''):'<tr><td colspan="5" class="empty">لا توجد جهات اتصال مطابقة.</td></tr>';
    const debts=currentSnapshot.debts.filter(row=>ids.has(String(row.contactId)));
    $('#ledgerDebts').innerHTML=debts.length?debts.map(row=>`<tr><td>${esc(row.contactName || currentSnapshot.contacts.find(person=>String(person.id)===String(row.contactId))?.name || '')}</td><td>${directionName(row.direction)}</td><td>${fmt(row.amount)} ₪</td><td>${fmt(row.paid)} ₪</td><td>${fmt(row.remaining)} ₪</td></tr>`).join(''):'<tr><td colspan="5" class="empty">لا توجد ديون مطابقة.</td></tr>';
    const transactions=currentSnapshot.transactions.filter(row=>ids.has(String(row.contactId))).slice(0,100);
    $('#ledgerTransactions').innerHTML=transactions.length?transactions.map(row=>`<tr><td>${esc(transactionName(row.kind))}${row.reversed?' · معكوسة':''}</td><td>${esc(row.contactName)}</td><td>${fmt(row.amount)} ₪</td><td>${esc(dateFmt(row.createdAt))}</td><td class="ledger-note">${esc(row.note)}</td></tr>`).join(''):'<tr><td colspan="5" class="empty">لا توجد حركات مطابقة.</td></tr>';
  }
  $('#ledgerSearch').addEventListener('input',renderLedger);

  startup();
})();
