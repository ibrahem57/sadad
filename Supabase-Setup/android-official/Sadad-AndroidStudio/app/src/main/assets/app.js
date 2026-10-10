(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const store = {
    get: (key, fallback = '') => { try { return localStorage.getItem(key) || fallback; } catch (_) { return fallback; } },
    set: (key, value) => { try { localStorage.setItem(key, String(value)); } catch (_) {} },
    remove: (key) => { try { localStorage.removeItem(key); } catch (_) {} }
  };
  const session = readSession();
  const preferredTheme = store.get('theme', session.darkTheme ? 'dark' : 'light');
  document.documentElement.dataset.theme = preferredTheme;
  function readSession() {
    try { return JSON.parse(window.Sadad.getSessionInfo()); }
    catch (_) { return { authenticated: false, account: {}, forcePasswordChange: false, hasAppPassword: false, biometricEnabled: false }; }
  }
  const fmt = (value) => {
    const n = Number(value || 0);
    try { return new Intl.NumberFormat('ar-PS', { maximumFractionDigits: 2 }).format(n); }
    catch (_) { return String(n); }
  };
  const money = (value) => `${fmt(value)} ₪`;
  const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  const snapshot = readSnapshot();
  const screen = document.body.dataset.screen || '';
  const contactId = Number(store.get('currentContactId', '0')) || 0;
  let selectedDebtId = Number(store.get('currentDebtId', '0')) || 0;
  let selectedDirection = 'receivable';
  let debtAmount = '0';
  let activeContactFilter = 'all';

  function readSnapshot() {
    try {
      const data = JSON.parse(window.Sadad.getSnapshot());
      return data && data.ok === false ? emptySnapshot() : data;
    } catch (_) { return emptySnapshot(); }
  }
  function emptySnapshot() { return { contacts: [], debts: [], payments: [], transactions: [], totals: { receivable: 0, payable: 0, net: 0 } }; }
  function allowed(permission) { return !session.account?.permissions || session.account.permissions[permission] !== false; }
  function refreshBranding() {
    const source = document.documentElement.dataset.theme === 'dark' ? '../logo-dark.png' : '../logo-light.jpeg';
    $$('img[data-brand-logo], img[src*="logo-light"], img[src*="brand-mark"]').forEach(image => { image.src = source; image.dataset.brandLogo = 'true'; });
  }
  function showSadadToast(message) {
    let toast = $('#sadad-toast');
    if (!toast) {
      toast = document.createElement('div'); toast.id = 'sadad-toast'; toast.setAttribute('role', 'status');
      toast.style.cssText = 'position:fixed;z-index:99999;left:14px;right:14px;bottom:calc(84px + env(safe-area-inset-bottom,0px));max-width:480px;margin:auto;display:flex;align-items:center;gap:12px;padding:12px 16px;border-radius:18px;background:var(--toast-bg,#064b42);color:#fff;box-shadow:0 12px 32px #001b1766;opacity:0;transform:translateY(16px);transition:opacity .18s ease,transform .18s ease;pointer-events:none;font:600 15px Tajawal,sans-serif';
      toast.innerHTML = '<img id="sadad-toast-logo" alt="" style="width:38px;height:38px;object-fit:contain;border-radius:10px;background:#ffffff20;flex:none"><span id="sadad-toast-message" style="line-height:1.5"></span>';
      document.body.appendChild(toast);
    }
    const logo = $('#sadad-toast-logo'); if (logo) logo.src = document.documentElement.dataset.theme === 'dark' ? '../logo-dark.png' : '../logo-light.jpeg';
    const label = $('#sadad-toast-message'); if (label) label.textContent = String(message || '');
    toast.style.opacity = '1'; toast.style.transform = 'translateY(0)';
    clearTimeout(window.sadadToastTimer); window.sadadToastTimer = setTimeout(() => { toast.style.opacity = '0'; toast.style.transform = 'translateY(16px)'; }, 3000);
  }
  window.showSadadToast = showSadadToast;
  function invoke(method, payload) {
    try { return JSON.parse(window.Sadad[method](JSON.stringify(payload || {}))); }
    catch (_) { return { ok: false, message: 'تعذر حفظ البيانات.' }; }
  }
  function showMessage(message) {
    showSadadToast(message);
  }
  function go(route) {
    if (route === 'history' || route === 'reports') {
      if (!allowed('reports')) { showMessage('التقارير غير مفعّلة لحسابك.'); return; }
    }
    if (route === 'contacts' || route === 'contacts_empty') {
      if (!allowed('contacts')) { showMessage('جهات الاتصال غير مفعّلة لحسابك.'); return; }
    }
    if (window.Sadad && typeof window.Sadad.navigate === 'function') window.Sadad.navigate(route);
  }
  function currentContact() { return snapshot.contacts.find(c => Number(c.id) === contactId) || snapshot.contacts[0] || null; }
  function currentDebt() {
    let debt = snapshot.debts.find(d => Number(d.id) === selectedDebtId);
    if (!debt && contactId) debt = snapshot.debts.find(d => Number(d.contactId) === contactId && Number(d.remaining) > 0);
    if (!debt) debt = snapshot.debts.find(d => Number(d.remaining) > 0);
    if (debt) { selectedDebtId = Number(debt.id); store.set('currentDebtId', selectedDebtId); }
    return debt || null;
  }
  function personDebts(id) { return snapshot.debts.filter(d => Number(d.contactId) === Number(id)); }
  function personTransactions(id) { return snapshot.transactions.filter(t => Number(t.contactId) === Number(id)); }
  function initials(name) { return String(name || 'سدد').trim().split(/\s+/).slice(0, 2).map(w => w[0] || '').join(' '); }
  function niceDate(timestamp, options = { day: 'numeric', month: 'short', year: 'numeric' }) {
    if (!timestamp) return '—';
    try { return new Date(Number(timestamp)).toLocaleDateString('ar-PS', options); } catch (_) { return '—'; }
  }
  function niceTime(timestamp) {
    if (!timestamp) return '';
    try { return new Date(Number(timestamp)).toLocaleTimeString('ar-PS', { hour: 'numeric', minute: '2-digit' }); } catch (_) { return ''; }
  }
  function directionLabel(direction) { return direction === 'payable' ? 'مستحق عليّ' : 'مستحق لي'; }
  function directionType(contact) {
    if (Number(contact.receivable) > 0 && Number(contact.payable) === 0) return 'mine';
    if (Number(contact.payable) > 0 && Number(contact.receivable) === 0) return 'theirs';
    if (Number(contact.receivable) === 0 && Number(contact.payable) === 0 && Number(contact.transactionCount) > 0) return 'settled';
    return 'all';
  }
  function textLeafNodes(root = document) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) if (walker.currentNode.nodeValue.trim()) nodes.push(walker.currentNode);
    return nodes;
  }
  function findLeaf(value, root = document) {
    return textLeafNodes(root).find(node => node.nodeValue.trim() === value) || null;
  }
  function replaceLeaf(oldText, newText, root = document) {
    textLeafNodes(root).forEach(node => {
      const raw = node.nodeValue;
      if (raw.trim() === oldText) node.nodeValue = raw.replace(oldText, String(newText));
    });
  }
  function setMetric(label, value, root = document) {
    const leaf = findLeaf(label, root);
    if (!leaf) return;
    let parent = leaf.parentElement;
    for (let depth = 0; parent && depth < 5; depth++, parent = parent.parentElement) {
      const candidates = $$('span', parent).filter(span => span !== leaf.parentElement && /[0-9٠-٩]/.test(span.textContent));
      if (candidates.length) { candidates[0].textContent = String(value); return; }
    }
  }
  function share(subject, content) {
    if (window.Sadad && typeof window.Sadad.shareText === 'function') window.Sadad.shareText(subject, content);
    else if (navigator.clipboard) navigator.clipboard.writeText(content).then(() => showMessage('تم نسخ النص.')).catch(() => showMessage(content));
    else showMessage(content);
  }
  function activeNav() {
    const path = ({ home: 'home', contacts: 'contacts', contacts_empty: 'contacts', contact: 'contacts', contact_empty: 'contacts', history: 'history', reports: 'reports', settings: 'settings', whatsapp: 'settings' })[screen];
    $$('nav a[data-path]').forEach(link => {
      const linkPath = ({ people: 'contacts', ledger: 'reports' })[link.dataset.path] || link.dataset.path;
      const selected = linkPath === path || (screen === 'history' && linkPath === 'reports');
      link.classList.toggle('text-secondary', selected);
      link.classList.toggle('font-bold', selected);
      link.classList.toggle('text-on-surface-variant', !selected);
      const icon = $('.material-symbols-outlined', link);
      if (icon) icon.style.fontVariationSettings = selected ? "'FILL' 1" : "'FILL' 0";
      link.addEventListener('click', event => {
        event.preventDefault();
        if (link.dataset.path === 'contacts' || link.dataset.path === 'people') openContacts();
        else if (link.dataset.path === 'history' || link.dataset.path === 'ledger' || link.dataset.path === 'reports') go('reports');
        else go(link.dataset.path === 'home' ? 'home' : link.dataset.path);
      });
    });
  }
  function openContacts() { go(snapshot.contacts.length ? 'contacts' : 'contacts_empty'); }
  function chooseContact(contact) {
    store.set('currentContactId', contact.id);
    store.remove('currentDebtId');
    const flow = store.get('nextFlow');
    if (flow === 'debt_add') {
      store.remove('nextFlow');
      go('debt_add');
    } else if (flow === 'payment') {
      store.remove('nextFlow');
      const debt = snapshot.debts.find(item => Number(item.contactId) === Number(contact.id) && Number(item.remaining) > 0);
      if (debt) { store.set('currentDebtId', debt.id); go('payment'); }
      else { showMessage('لا يوجد لهذا الشخص دين مفتوح لتسجيل دفعة.'); go(personTransactions(contact.id).length ? 'contact' : 'contact_empty'); }
    } else {
      const hasTransactions = personTransactions(contact.id).length > 0;
      go(hasTransactions ? 'contact' : 'contact_empty');
    }
  }
  function showAddDebt() {
    if (!allowed('debts')) { showMessage('إضافة الديون غير مفعّلة لهذا المتجر.'); return; }
    if (!snapshot.contacts.length) {
      store.set('nextFlow', 'debt_add');
      go('contact_add');
      return;
    }
    const person = currentContact();
    if (person) {
      store.set('currentContactId', person.id);
      store.set('nextFlow', 'debt_add');
      go('debt_add');
      return;
    }
    store.set('nextFlow', 'debt_add');
    openContacts();
  }
  function choosePersonForDebt() {
    if (!allowed('debts')) { showMessage('إضافة الديون غير مفعّلة لهذا المتجر.'); return; }
    store.set('nextFlow', 'debt_add');
    if (!snapshot.contacts.length) go('contact_add');
    else go('contacts');
  }
  function showPayment() {
    if (!allowed('payments')) { showMessage('تسجيل الدفعات غير مفعّل لهذا المتجر.'); return; }
    const debt = currentDebt();
    if (!debt) { showMessage('سجّل دينًا قبل تسجيل دفعة.'); return; }
    if (Number(debt.remaining) <= 0) { showMessage('تم سداد هذا الدين بالكامل.'); return; }
    store.set('currentContactId', debt.contactId);
    go('payment');
  }
  function showToast(message) {
    const toast = $('#toast-success') || $('#toast') || $('#successToast');
    if (!toast) { showMessage(message); return; }
    const msg = $('#toastMsg', toast) || $('.toast-message', toast);
    if (msg) msg.textContent = message;
    toast.classList.remove('hidden', 'opacity-0', 'pointer-events-none', 'translate-y-4');
    toast.classList.add('opacity-100', 'translate-y-0');
    setTimeout(() => toast.classList.add('opacity-0', 'pointer-events-none'), 2200);
  }

  function renderContact(contact) {
    const kind = directionType(contact);
    const balance = Number(contact.receivable) - Number(contact.payable);
    const amount = money(Math.abs(balance));
    const mine = kind === 'mine';
    const color = mine ? 'bg-secondary-container text-on-secondary-container' : 'bg-primary-fixed text-on-primary-fixed-variant';
    const label = kind === 'settled' ? 'مسدد' : directionLabel(mine ? 'receivable' : 'payable');
    const recent = snapshot.transactions.find(t => Number(t.contactId) === Number(contact.id));
    const meta = recent ? `${recent.kind === 'payment' ? 'آخر دفعة' : 'آخر دين'} • ${niceDate(recent.createdAt, { day: 'numeric', month: 'short' })}` : 'لا توجد معاملات بعد';
    return `<article class="contact-item group relative overflow-hidden bg-surface-container-lowest rounded-xl p-space-md shadow-sm transition-all duration-200 hover:shadow-md cursor-pointer active:scale-[0.99]" data-contact-id="${Number(contact.id)}" data-name="${esc(contact.name)}" data-phone="${esc(contact.phone)}" data-type="${kind}">
      <div class="flex items-center justify-between gap-space-sm"><div class="flex items-center gap-3 min-w-0">
      <div class="w-12 h-12 rounded-full ${color} flex items-center justify-center font-title-lg text-title-lg font-bold flex-shrink-0">${esc(initials(contact.name))}</div>
      <div class="flex flex-col min-w-0"><h2 class="font-title-lg text-title-lg text-on-surface truncate font-bold">${esc(contact.name)}</h2>
      <div class="flex items-center gap-1.5 mt-0.5 text-on-surface-variant font-body-sm text-body-sm"><span class="material-symbols-outlined text-[14px]">schedule</span><span>${esc(meta)}</span></div></div></div>
      <div class="flex flex-col items-end flex-shrink-0"><span class="font-headline-sm text-headline-sm font-bold ${mine ? 'text-secondary' : 'text-on-surface-variant'}">${amount}</span>
      <span class="inline-flex items-center gap-0.5 px-2 py-0.5 mt-0.5 rounded-full ${mine ? 'bg-secondary-container/50 text-on-secondary-container' : 'bg-surface-container text-on-surface-variant'} font-label-sm text-label-sm font-bold"><span class="material-symbols-outlined text-[12px]">${kind === 'settled' ? 'done_all' : mine ? 'north_east' : 'south_west'}</span>${label}</span></div></div>
      <div class="w-full h-1 bg-surface-container-high rounded-full mt-3 overflow-hidden"><div class="h-full bg-secondary rounded-full" style="width:${recent ? '100' : '0'}%"></div></div></article>`;
  }
  function initContacts() {
    const container = $('#contactsContainer');
    if (!container) return;
    const bar = $('#filterBar');
    const chips = bar ? $$('.filter-chip', bar) : [];
    activeContactFilter = store.get('contactsFilter', 'all');
    function draw() {
      const search = ($('#searchContactsInput')?.value || '').trim().toLocaleLowerCase();
      const list = snapshot.contacts.filter(contact => {
        const query = `${contact.name} ${contact.phone}`.toLocaleLowerCase();
        return (!search || query.includes(search)) && (activeContactFilter === 'all' || directionType(contact) === activeContactFilter);
      });
      container.innerHTML = list.length ? list.map(renderContact).join('') : `<div class="bg-surface-container-lowest rounded-xl p-space-lg text-center text-on-surface-variant">لا توجد نتائج مطابقة.</div>`;
      $$('.contact-item', container).forEach(card => card.addEventListener('click', () => {
        const person = snapshot.contacts.find(c => Number(c.id) === Number(card.dataset.contactId));
        if (person) chooseContact(person);
      }));
      chips.forEach(button => {
        const type = button.dataset.filter || 'all';
        const count = type === 'all' ? snapshot.contacts.length : snapshot.contacts.filter(c => directionType(c) === type).length;
        const label = $('.material-symbols-outlined', button)?.nextElementSibling || button.querySelector('span:last-child');
        if (label) label.textContent = `${type === 'all' ? 'الكل' : type === 'mine' ? 'لي' : type === 'theirs' ? 'عليّ' : 'مسدد'} (${fmt(count)})`;
        const on = type === activeContactFilter;
        button.setAttribute('aria-selected', on ? 'true' : 'false');
        button.classList.toggle('bg-primary', on);
        button.classList.toggle('text-on-primary', on);
        button.classList.toggle('bg-surface-container-lowest', !on);
      });
    }
    chips.forEach(button => button.addEventListener('click', () => { activeContactFilter = button.dataset.filter || 'all'; store.set('contactsFilter', activeContactFilter); draw(); }));
    $('#searchContactsInput')?.addEventListener('input', draw);
    draw();
  }

  function initHome() {
    const total = snapshot.totals || { receivable: 0, payable: 0, net: 0 };
    setMetric('صافي الرصيد الحالي', money(total.net));
    setMetric('مستحق لي', fmt(total.receivable));
    setMetric('مستحق عليّ', fmt(total.payable));
    const netLabel = findLeaf('الوضع المالي متوازن');
    if (netLabel) netLabel.nodeValue = total.net > 0 ? 'لديك رصيد مستحق' : total.net < 0 ? 'عليك رصيد مستحق' : 'لا توجد أرصدة مفتوحة';
    const due = findLeaf('استحقاقات قريبة');
    if (due) {
      let node = due.parentElement;
      while (node && !(node.classList.contains('flex-col') && node.classList.contains('gap-1.5'))) node = node.parentElement;
      if (node) node.classList.add('hidden');
    }
    const recentTitle = findLeaf('آخر الحركات');
    if (recentTitle) {
      let node = recentTitle.parentElement;
      for (let i = 0; i < 4 && node; i++, node = node.parentElement) {
        if (node.classList.contains('flex-col') && node.classList.contains('gap-2')) { node.classList.add('hidden'); break; }
      }
    }
    const content = $('main > div');
    if (content) {
      const section = document.createElement('section');
      section.className = 'flex flex-col gap-2';
      const recent = snapshot.transactions.slice(0, 3);
      section.innerHTML = `<div class="flex items-center justify-between px-0.5"><span class="font-title-md text-title-md text-on-surface font-bold">آخر الحركات</span><button type="button" class="font-label-md text-label-md text-secondary">عرض السجل الكامل</button></div>${recent.length ? `<div class="flex flex-col gap-2">${recent.map(renderCompactTransaction).join('')}</div>` : '<div class="bg-surface-container-lowest p-space-md rounded-xl text-body-md text-on-surface-variant">ستظهر هنا أحدث الديون والدفعات بعد تسجيلها.</div>'}`;
      $('button', section)?.addEventListener('click', () => go('history'));
      $$('[data-tx-debt]', section).forEach(row => row.addEventListener('click', () => openDebt(row.dataset.txDebt)));
      content.appendChild(section);
    }
    $('#add-debt-btn')?.addEventListener('click', choosePersonForDebt);
    $('#record-pay-btn')?.addEventListener('click', showPayment);
    $$('button').filter(button=>/مستحق لي|مستحق عليّ/.test(button.textContent)).forEach(button=>button.addEventListener('click',()=>{store.set('contactsFilter',button.textContent.includes('عليّ')?'theirs':'mine');openContacts();}));
    $('#close-sheet-btn')?.addEventListener('click', () => $('#quick-action-sheet')?.classList.add('hidden'));
  }
  function renderCompactTransaction(tx) {
    const payment = tx.kind === 'payment';
    const label = payment ? 'دفعة مسجلة' : directionLabel(tx.direction);
    const sign = payment ? '−' : '+';
    return `<button type="button" data-tx-debt="${Number(tx.debtId)}" class="w-full bg-surface-container-lowest p-3 rounded-xl shadow-sm flex items-center justify-between text-right"><span class="flex items-center gap-3 min-w-0"><span class="w-10 h-10 rounded-full flex items-center justify-center bg-secondary-container text-on-secondary-container"><span class="material-symbols-outlined">${payment ? 'payments' : 'receipt_long'}</span></span><span class="flex flex-col min-w-0"><span class="font-title-md text-title-md text-on-surface truncate font-bold">${esc(tx.contactName)}</span><span class="font-body-sm text-body-sm text-on-surface-variant">${label} • ${niceDate(tx.createdAt)}</span></span></span><span class="font-headline-sm text-headline-sm text-secondary font-bold">${sign}${money(tx.amount)}</span></button>`;
  }

  function initContactDetails() {
    const person = currentContact();
    if (!person) { openContacts(); return; }
    replaceLeaf('أحمد سالم', person.name);
    replaceLeaf('طارق العلي', person.name);
    const nameHeadings = $$('h1,h2');
    nameHeadings.forEach(el => { if (/أحمد سالم|طارق العلي/.test(el.textContent)) el.textContent = person.name; });
    const phoneNodes = $$('[dir="ltr"]');
    phoneNodes.forEach(el => { if (/\d/.test(el.textContent)) el.textContent = person.phone || 'رقم الهاتف غير مضاف'; });
    const initialsNodes = $$('.font-title-lg, .font-headline-sm');
    initialsNodes.forEach(el => { if (/^(أ\s*س|ط\s*ع)$/.test(el.textContent.trim())) el.textContent = initials(person.name); });
    setMetric('مستحق لك', money(person.receivable));
    setMetric('مستحق عليك', money(person.payable));
    setMetric('صافي الرصيد الحالي', money(person.net));
    const tx = personTransactions(person.id);
    const existingRows = $$('.ledger-item');
    if (existingRows.length) {
      const holder = existingRows[0].parentElement;
      holder.innerHTML = tx.map(renderLedgerRow).join('') || '<div class="bg-surface-container-lowest rounded-xl p-space-md text-on-surface-variant">لا توجد معاملات مسجلة بعد.</div>';
      $$('[data-tx-debt]', holder).forEach(row => row.addEventListener('click', () => openDebt(row.dataset.txDebt)));
    }
    const count = $('.font-label-sm.px-2.py-0\\.5.rounded-full.bg-surface-container');
    if (count) count.textContent = `${fmt(tx.length)} حركات`;
    $$('.ledger-filter').forEach(btn => btn.addEventListener('click', () => {
      const type = btn.dataset.filter;
      $$('.ledger-item').forEach(row => row.classList.toggle('hidden', type !== 'all' && row.dataset.type !== type));
    }));
    $('#openRecordPayment')?.addEventListener('click', showPayment);
    $('#openAddDebt')?.addEventListener('click', showAddDebt);
    $('#callBtn')?.addEventListener('click', () => openPhone(person.phone));
    $('#shareTopBtn')?.addEventListener('click', () => shareStatement(person));
    $('#whatsappShareBtn')?.addEventListener('click', () => shareStatement(person));
    if (allowed('whatsapp')) {
      const actionRoot = $('main > div');
      if (actionRoot && !$('#whatsappStatementButton')) {
        const sendButton = document.createElement('button'); sendButton.id = 'whatsappStatementButton';
        sendButton.className = 'w-full h-12 rounded-xl bg-secondary-container text-on-secondary-container font-title-md text-title-md font-bold flex items-center justify-center gap-space-xs';
        sendButton.innerHTML = '<span class="material-symbols-outlined">chat</span>إرسال كشف الحساب عبر WhatsApp';
        actionRoot.insertBefore(sendButton, actionRoot.children[2] || null);
        sendButton.addEventListener('click', () => {
          if (!person.whatsappOptIn) { showMessage('سجّل موافقة الشخص على استقبال رسائل WhatsApp قبل الإرسال.'); return; }
          if (!person.phone) { showMessage('أضف رقم هاتف لهذا الشخص أولاً.'); return; }
          try { const result = JSON.parse(window.Sadad.sendWhatsAppReport(person.id)); if (!result.ok) showMessage(result.message); else showMessage('تم إرسال كشف الحساب عبر WhatsApp.'); }
          catch (_) { showMessage('تعذر إرسال كشف WhatsApp. تحقق من الربط والإنترنت.'); }
        });
      }
    }
    $('#closePaymentModal')?.addEventListener('click', () => $('#paymentModal')?.classList.add('hidden'));
    $('button[aria-label="خيارات إضافية"]')?.addEventListener('click',()=>showActionMenu('خيارات الحساب',[{label:'إضافة دين',action:showAddDebt},{label:'تسجيل دفعة',action:showPayment},{label:'مشاركة كشف الحساب',action:()=>shareStatement(person)}]));
  }
  function renderLedgerRow(tx) {
    const isPayment = tx.kind === 'payment';
    const sign = isPayment ? '−' : '+';
    const label = isPayment ? 'دفعة مسجلة' : directionLabel(tx.direction);
    return `<button type="button" data-type="${isPayment ? 'payment' : 'debt'}" data-tx-debt="${Number(tx.debtId)}" class="ledger-item group relative w-full flex items-start gap-3 p-space-md rounded-xl bg-surface-container-lowest shadow-sm text-right"><span class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shrink-0"><span class="material-symbols-outlined">${isPayment ? 'payments' : 'receipt_long'}</span></span><span class="flex-1 min-w-0"><span class="flex items-center justify-between gap-2"><span class="font-title-md text-title-md text-on-surface">${label}</span><span class="font-title-lg text-title-lg text-secondary font-bold">${sign}${money(tx.amount)}</span></span><span class="flex items-center gap-2 text-on-surface-variant font-body-sm text-body-sm mt-1">${niceDate(tx.createdAt)} • ${esc(tx.note || (isPayment ? tx.method : ''))}</span></span></button>`;
  }
  function shareStatement(person) {
    const content = `كشف حساب ${person.name}\nمستحق لي: ${money(person.receivable)}\nمستحق عليّ: ${money(person.payable)}\nصافي الرصيد: ${money(person.net)}\n— تطبيق سدد`;
    share('كشف حساب - سدد', content);
  }
  function openPhone(phone) {
    const digits = String(phone || '').replace(/[^+\d]/g, '');
    if (digits) window.location.href = `tel:${digits}`;
    else showMessage('أضف رقم هاتف جهة الاتصال أولاً.');
  }

  function initAddContact() {
    const name = $('#contactName');
    const phone = $('#contactPhone');
    const note = $('#contactNote');
    const chips = $$('.category-chip');
    chips.forEach(chip => chip.addEventListener('click', () => {
      chips.forEach(c => { c.classList.remove('bg-secondary', 'text-on-secondary'); c.classList.add('bg-surface-container-lowest', 'text-on-surface'); });
      chip.classList.remove('bg-surface-container-lowest', 'text-on-surface');
      chip.classList.add('bg-secondary', 'text-on-secondary');
    }));
    const submit = $('#submitBtn');
    submit?.addEventListener('click', event => {
      event.preventDefault();
      if (!name?.value.trim()) { name?.focus(); showMessage('أدخل اسم الشخص.'); return; }
      const chosen = chips.find(chip => chip.classList.contains('bg-secondary'));
      const label = chosen?.textContent.trim() || 'صديق';
      if (!allowed('contacts')) { showMessage('إنشاء جهات الاتصال غير مفعّل لهذا المتجر.'); return; }
      const result = invoke('saveContact', { name: name.value.trim(), phone: phone?.value.trim() || '', category: label, note: note?.value.trim() || '', whatsappOptIn: $('#whatsappConsent')?.checked || false });
      if (!result.ok) { showMessage(result.message || 'تعذر حفظ الشخص.'); return; }
      store.set('currentContactId', result.id);
      const flow = store.get('nextFlow');
      if (flow === 'debt_add') { store.remove('nextFlow'); go('debt_add'); }
      else go('contact_empty');
    });
    $('button[aria-label="رجوع"]')?.addEventListener('click', event => { event.preventDefault(); window.history.back(); });
  }

  function updateDebtSummary() {
    const person = currentContact();
    const display = $('#amountDisplay');
    const amount = Number(String(display?.textContent || debtAmount).replace(/[^0-9.]/g, '')) || 0;
    const name = person?.name || 'الشخص المحدد';
    const summary = $('#summaryText');
    if (summary) summary.textContent = `أنت تسجل ديناً بقيمة ${money(amount)} مستحق ${selectedDirection === 'receivable' ? 'لك (لي) عند' : 'عليك (التزام) لصالح'} ${name}.`;
    const personName = $('#personName');
    if (personName && person) personName.textContent = person.name;
    const personPhone = $('#personPhone');
    if (personPhone && person) personPhone.textContent = person.phone || '';
  }
  function setDebtDirection(direction) {
    selectedDirection = direction === 'payable' ? 'payable' : 'receivable';
    const a = $('#typeForMe');
    const b = $('#typeOnMe');
    if (a && b) {
      a.className = `direction-btn relative flex flex-col items-center justify-center p-3 rounded-xl ${selectedDirection === 'receivable' ? 'bg-secondary-fixed text-on-secondary-fixed' : 'bg-surface-container-lowest text-on-surface-variant'} transition-all shadow-sm`;
      b.className = `direction-btn relative flex flex-col items-center justify-center p-3 rounded-xl ${selectedDirection === 'payable' ? 'bg-error-container text-on-error-container' : 'bg-surface-container-lowest text-on-surface-variant'} transition-all shadow-sm`;
      $('#checkForMe')?.classList.toggle('hidden', selectedDirection !== 'receivable');
      $('#checkOnMe')?.classList.toggle('hidden', selectedDirection !== 'payable');
    }
    updateDebtSummary();
  }
  function initAddDebt() {
    const person = currentContact();
    if (!person) { store.set('nextFlow', 'debt_add'); openContacts(); return; }
    $('#personName') && ($('#personName').textContent = person.name);
    $('#personPhone') && ($('#personPhone').textContent = person.phone || '');
    const amountEl = $('#amountDisplay');
    if (amountEl) amountEl.textContent = '0';
    $('#noteInput') && ($('#noteInput').value = '');
    debtAmount = '0';
    selectedDirection = 'receivable';
    function updateAmount(value) { debtAmount = String(value); if (amountEl) amountEl.textContent = debtAmount || '0'; updateDebtSummary(); }
    $$('.keypad-btn').forEach(button => button.addEventListener('click', () => {
      const key = button.textContent.trim();
      if (key === '.') { if (!debtAmount.includes('.')) updateAmount(debtAmount ? debtAmount + '.' : '0.'); return; }
      if (!/^\d$/.test(key)) return;
      updateAmount(debtAmount === '0' ? key : (debtAmount.length < 10 ? debtAmount + key : debtAmount));
    }));
    $('#backspaceBtn')?.addEventListener('click', () => updateAmount(debtAmount.slice(0, -1) || '0'));
    $('#clearBtn')?.addEventListener('click', () => updateAmount('0'));
    $$('.preset-chip').forEach(chip => chip.addEventListener('click', () => {
      const add = Number(chip.dataset.value || 0);
      updateAmount(String((Number(debtAmount) || 0) + add));
    }));
    $('#typeForMe')?.addEventListener('click', () => setDebtDirection('receivable'));
    $('#typeOnMe')?.addEventListener('click', () => setDebtDirection('payable'));
    $('#changePersonBtn')?.addEventListener('click', () => { store.set('nextFlow', 'debt_add'); openContacts(); });
    $('#saveDebtBtn')?.addEventListener('click', event => {
      event.preventDefault();
      const amount = Number(debtAmount);
      if (!Number.isFinite(amount) || amount <= 0) { showMessage('أدخل مبلغًا أكبر من صفر.'); return; }
      if (!allowed('debts')) { showMessage('إضافة الديون غير مفعّلة لهذا المتجر.'); return; }
      const openPeople = new Set(snapshot.debts.filter(d => Number(d.remaining) > 0).map(d => Number(d.contactId)));
      if (!openPeople.has(Number(person.id)) && openPeople.size >= Number(session.account?.debtorLimit ?? 1000000)) { showMessage(`وصلت إلى حد ${fmt(session.account?.debtorLimit)} شخصاً مديناً الذي حدده الأدمن.`); return; }
      const result = invoke('saveDebt', { contactId: person.id, direction: selectedDirection, amount: amount.toFixed(2), note: $('#noteInput')?.value.trim() || '', dueDate: $('#dueDateInput')?.value || '' });
      if (!result.ok) { showMessage(result.message || 'تعذر حفظ الدين.'); return; }
      store.set('currentDebtId', result.id);
      go('contact');
    });
    $('#dueDateToggle')?.addEventListener('click', () => {
      const box = $('#dueDateContainer');
      if (!box) return;
      let input = $('#dueDateInput');
      if (!input) {
        input = document.createElement('input');
        input.type = 'date'; input.id = 'dueDateInput';
        input.className = 'w-full h-12 rounded-xl bg-surface-container-low px-space-sm text-on-surface';
        box.appendChild(input);
      }
      box.classList.toggle('hidden');
    });
    $('#summaryText') && (updateDebtSummary());
    setDebtDirection('receivable');
  }

  function initPayment() {
    const debt = currentDebt();
    if (!debt || Number(debt.remaining) <= 0) { showMessage('لا يوجد دين مفتوح لتسجيل دفعة.'); openContacts(); return; }
    const person = snapshot.contacts.find(c => Number(c.id) === Number(debt.contactId));
    if (person) {
      replaceLeaf('أحمد سالم', person.name);
      $$('h1,h2,h3').forEach(el => { if (el.textContent.trim() === 'أحمد سالم') el.textContent = person.name; });
      $$('[dir="ltr"]').forEach(el => { if (/\d/.test(el.textContent)) el.textContent = person.phone || ''; });
    }
    const remaining = Number(debt.remaining);
    const input = $('#payment-input');
    if (input) { input.max = String(remaining); input.value = String(Math.min(100, remaining)); }
    const screenDebt = $('#current-debt-display');
    if (screenDebt) screenDebt.textContent = money(remaining);
    const update = () => {
      const paid = Math.max(0, Math.min(remaining, Number(input?.value || 0)));
      const rest = Math.max(0, remaining - paid);
      if ($('#remaining-balance-value')) $('#remaining-balance-value').textContent = fmt(rest);
      if ($('#progress-bar-fill')) $('#progress-bar-fill').style.width = `${remaining ? (rest / remaining) * 100 : 0}%`;
      if ($('#settled-portion-label')) $('#settled-portion-label').textContent = money(paid);
      if ($('#unsettled-portion-label')) $('#unsettled-portion-label').textContent = money(rest);
      if ($('#btn-label-text')) $('#btn-label-text').textContent = `تأكيد تسجيل الدفعة (${money(paid)})`;
      return paid;
    };
    input?.addEventListener('input', update);
    let paymentDate = Date.now();
    const dateLabel = $('#paymentDateLabel');
    const changeDate = $('#changePaymentDate');
    const dateInput = document.createElement('input'); dateInput.type = 'date'; dateInput.id = 'paymentDatePicker'; dateInput.className = 'absolute opacity-0 pointer-events-none'; dateInput.style.cssText='width:1px;height:1px;'
    const localDate = new Date(); dateInput.value = `${localDate.getFullYear()}-${String(localDate.getMonth()+1).padStart(2,'0')}-${String(localDate.getDate()).padStart(2,'0')}`; if(dateLabel)dateLabel.textContent=localDate.toLocaleDateString('ar-PS',{day:'numeric',month:'long',year:'numeric'});
    changeDate?.parentElement?.appendChild(dateInput);
    changeDate?.addEventListener('click', () => { try { if (dateInput.showPicker) dateInput.showPicker(); else dateInput.click(); } catch (_) { dateInput.click(); } });
    dateInput.addEventListener('change', () => {
      if (!dateInput.value) return;
      const [year,month,day]=dateInput.value.split('-').map(Number); const selected=new Date(year,month-1,day,0,0,0,0);
      if (selected.getTime() > Date.now()) { showMessage('لا يمكن تسجيل دفعة بتاريخ مستقبلي.'); dateInput.value=`${localDate.getFullYear()}-${String(localDate.getMonth()+1).padStart(2,'0')}-${String(localDate.getDate()).padStart(2,'0')}`; return; }
      paymentDate=selected.getTime(); if(dateLabel)dateLabel.textContent=selected.toLocaleDateString('ar-PS',{day:'numeric',month:'long',year:'numeric'});
    });
    window.setQuickAmount = amount => { if (input) input.value = String(Math.min(Number(amount), remaining)); update(); };
    window.selectMethod = method => { const radio = $(`input[name="payment_method"][value="${method}"]`); if (radio) radio.checked = true; };
    window.hideToast = () => $('#toast-success')?.classList.add('opacity-0', 'pointer-events-none', 'translate-y-4');
    window.confirmPayment = () => {
      const value = update();
      if (!value || value <= 0) { showMessage('أدخل مبلغ الدفعة.'); return; }
      const method = $('input[name="payment_method"]:checked')?.value || 'cash';
      const result = invoke('savePayment', { debtId: debt.id, amount: value.toFixed(2), method, note: $('#payment-note')?.value.trim() || '', createdAt: paymentDate });
      if (!result.ok) { showMessage(result.message || 'تعذر تسجيل الدفعة.'); return; }
      showMessage('تم تسجيل الدفعة وتحديث الرصيد.');
      setTimeout(() => go(personTransactions(person?.id || debt.contactId).length ? 'contact' : 'contact_empty'), 250);
    };
    $('#confirm-submit-btn')?.addEventListener('click', event => { event.preventDefault(); window.confirmPayment(); });
    $$('button').find(button => button.textContent.trim() === 'تغيير')?.addEventListener('click', () => { store.set('nextFlow','payment'); store.set('contactsFilter','all'); openContacts(); });
    $$('.quick-btn').forEach(button => button.addEventListener('click', () => window.setQuickAmount(Number(button.textContent.replace(/[^0-9٠-٩]/g, '').replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d))))));
    update();
  }

  function initHistory() {
    const search = $$('input').find(input => input.placeholder?.includes('ابحث'));
    const searchButton = $('button[aria-label="البحث في الحركات"]');
    searchButton?.addEventListener('click', () => search?.focus());
    let kind = 'all', period = 'current', customMonth = '';
    const filterGroup = search?.parentElement?.nextElementSibling;
    const periodGroup = filterGroup?.nextElementSibling;
    const kindButtons = filterGroup ? $$('button', filterGroup) : [];
    const periodButtons = periodGroup ? $$('button', periodGroup) : [];
    const holder = $$('.cursor-pointer', $('main') || document)[0]?.parentElement;
    function drawHistory() {
      const now = new Date(); now.setHours(0,0,0,0);
      let start = new Date(now.getFullYear(),now.getMonth(),1), end = new Date(now.getFullYear(),now.getMonth()+1,1);
      if (period === 'previous') { start = new Date(now.getFullYear(),now.getMonth()-1,1); end = new Date(now.getFullYear(),now.getMonth(),1); }
      if (period === 'custom' && customMonth) { const [y,m]=customMonth.split('-').map(Number); start=new Date(y,m-1,1); end=new Date(y,m,1); }
      const periodTransactions=snapshot.transactions.filter(tx=>Number(tx.createdAt)>=start.getTime()&&Number(tx.createdAt)<end.getTime());
      const receipts=periodTransactions.filter(tx=>tx.kind==='payment').reduce((sum,tx)=>sum+Number(tx.amount||0),0);
      const newDebts=periodTransactions.filter(tx=>tx.kind==='debt').reduce((sum,tx)=>sum+Number(tx.amount||0),0);
      if($('#historyReceivedTotal'))$('#historyReceivedTotal').textContent=fmt(receipts);
      if($('#historyDebtTotal'))$('#historyDebtTotal').textContent=fmt(newDebts);
      const query=(search?.value||'').trim().toLocaleLowerCase();
      const list=snapshot.transactions.filter(tx=>{
        const date=Number(tx.createdAt); const isDate=date>=start.getTime()&&date<end.getTime();
        const isKind=kind==='all'||(kind==='payment'&&tx.kind==='payment')||(kind==='receivable'&&tx.kind==='debt'&&tx.direction==='receivable')||(kind==='payable'&&tx.kind==='debt'&&tx.direction==='payable');
        return isDate&&isKind&&(!query||`${tx.contactName} ${tx.note||''} ${tx.kind==='payment'?paymentMethod(tx.method):directionLabel(tx.direction)}`.toLocaleLowerCase().includes(query));
      }).sort((a,b)=>Number(b.createdAt)-Number(a.createdAt));
      if($('#historyTopCount'))$('#historyTopCount').textContent=`${fmt(list.length)} حركة`;
      if(holder) holder.innerHTML=`<div class="flex items-center justify-between px-1"><span class="font-label-lg text-label-lg text-on-surface-variant font-bold">الحركات</span><span class="font-label-sm text-label-sm text-outline">${fmt(list.length)} حركة</span></div>${list.length?list.map(tx=>`<button type="button" data-tx-debt="${Number(tx.debtId)}" class="flex items-center justify-between gap-3 p-3.5 bg-surface-container-lowest rounded-xl shadow-sm text-right w-full"><span class="flex items-center gap-3 min-w-0"><span class="w-11 h-11 rounded-full bg-secondary-container text-secondary flex items-center justify-center"><span class="material-symbols-outlined">${tx.kind==='payment'?'payments':'receipt_long'}</span></span><span class="flex flex-col min-w-0"><b class="font-title-md text-title-md truncate">${esc(tx.contactName)}</b><span class="font-body-sm text-body-sm text-on-surface-variant">${tx.kind==='payment'?'دفعة مسجلة':directionLabel(tx.direction)} • ${niceDate(tx.createdAt)} ${niceTime(tx.createdAt)}</span>${tx.note?`<span class="font-body-sm text-body-sm text-outline truncate">${esc(tx.note)}</span>`:''}</span></span><span class="font-title-lg text-title-lg font-bold text-secondary">${tx.kind==='payment'?'−':'+'}${money(tx.amount)}</span></button>`).join(''):'<div class="bg-surface-container-lowest rounded-xl p-space-lg text-center text-on-surface-variant">لا توجد حركات مطابقة للفترة المحددة.</div>'}`;
      $$('[data-tx-debt]',holder||document).forEach(row=>row.addEventListener('click',()=>openDebt(row.dataset.txDebt)));
      kindButtons.forEach((button,index)=>{const on=index===({all:0,payment:1,receivable:2,payable:3}[kind]);button.classList.toggle('bg-primary',on);button.classList.toggle('text-on-primary',on);button.classList.toggle('text-on-surface-variant',!on);});
      periodButtons.forEach((button,index)=>{const on=index===({current:0,previous:1,custom:2}[period]);button.classList.toggle('bg-surface-container-lowest',on);button.classList.toggle('text-primary',on);button.classList.toggle('font-bold',on);});
    }
    kindButtons.forEach((button,index)=>button.addEventListener('click',()=>{kind=['all','payment','receivable','payable'][index]||'all';drawHistory();}));
    periodButtons.forEach((button,index)=>button.addEventListener('click',()=>{
      if(index===2){const value=window.prompt('اكتب الشهر المطلوب بصيغة YYYY-MM',customMonth||`${new Date().getFullYear()}-${String(new Date().getMonth()+1).padStart(2,'0')}`);if(value==null)return;if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(value.trim())){showMessage('استخدم صيغة سنة-شهر مثل 2026-09.');return;}customMonth=value.trim();period='custom';}
      else period=index===1?'previous':'current'; drawHistory();
    }));
    search?.addEventListener('input',drawHistory); drawHistory();
    $('#closeSheetBtn')?.addEventListener('click', () => $('#transactionSheet')?.classList.add('hidden'));
    $('#closeSheetBtnSecondary')?.addEventListener('click', () => $('#transactionSheet')?.classList.add('hidden'));
    $('#modalBackdrop')?.classList.add('hidden');
    $('#transactionSheet')?.classList.add('hidden');
  }
  function openDebt(id) { if (!id || Number(id) <= 0) return; store.set('currentDebtId', id); const debt = snapshot.debts.find(d => Number(d.id) === Number(id)); if (debt) store.set('currentContactId', debt.contactId); go('debt'); }

  function initDebtDetails() {
    const debt = currentDebt();
    if (!debt) { showMessage('لا توجد بيانات دين لعرضها.'); openContacts(); return; }
    store.set('currentContactId', debt.contactId);
    const person = snapshot.contacts.find(c => Number(c.id) === Number(debt.contactId));
    if (person) {
      $$('h2,h3').forEach(el => { if (el.textContent.trim() === 'أحمد سالم') el.textContent = person.name; });
      $$('[dir="ltr"]').forEach(el => { if (/\d/.test(el.textContent)) el.textContent = person.phone || ''; });
    }
    const main = $('main');
    const hero = $('.bg-primary-container', main || document);
    if (hero) {
      const amount = $('.font-display-lg', hero);
      if (amount) amount.textContent = fmt(debt.remaining);
      const percent = debt.amount ? Math.round(debt.paid / debt.amount * 100) : 0;
      const progress = $$('span', hero).find(el => /%\s*\(/.test(el.textContent));
      if (progress) progress.textContent = `${fmt(percent)}% (تم سداد ${money(debt.paid)})`;
      const bars = $$('div', hero).filter(el => el.style && el.style.width && el.style.width.includes('%'));
      if (bars.length) bars[bars.length - 1].style.width = `${percent}%`;
      const labelNode = findLeaf(directionLabel(debt.direction), hero);
      if (labelNode) labelNode.nodeValue = labelNode.nodeValue.replace(directionLabel(debt.direction), directionLabel(debt.direction));
      replaceLeaf('500 ₪', money(debt.amount), hero);
      replaceLeaf('350', fmt(debt.remaining), hero);
      replaceLeaf('150 ₪', money(debt.paid), hero);
    }
    if (main) {
      const noteLabel = findLeaf('البيان والملاحظة', main);
      if (noteLabel) {
        let wrap = noteLabel.parentElement;
        for (let i = 0; i < 3 && wrap; i++, wrap = wrap.parentElement) {
          const p = $('p', wrap);
          if (p) { p.textContent = debt.note || 'لا توجد ملاحظة مرفقة.'; break; }
        }
      }
      const active = findLeaf('مستحق لي (أنت الدائن)', main);
      if (active) active.nodeValue = active.nodeValue.replace('مستحق لي (أنت الدائن)', directionLabel(debt.direction));
      const debtList = findLeaf('الدفعات المرتبطة بهذا الدين', main);
      if (debtList) {
        const section = debtList.parentElement?.parentElement?.parentElement;
        const payments = snapshot.payments.filter(p => Number(p.debtId) === Number(debt.id));
        if (section) {
          const badge = section.children[0]?.querySelector('span');
          if (badge) badge.textContent = `${payments.length} ${payments.length === 1 ? 'دفعة مسجلة' : 'دفعات مسجلة'}`;
          const list = section.children[1];
          if (list) list.innerHTML = payments.length ? payments.map((p, i) => `<div class="bg-surface-container-lowest rounded-xl p-space-md shadow-sm flex flex-col space-y-space-sm"><div class="flex items-start justify-between"><div class="flex items-center gap-space-sm"><div class="w-10 h-10 rounded-xl bg-secondary-container text-on-secondary-container flex items-center justify-center"><span class="material-symbols-outlined text-[22px]">payments</span></div><div class="flex flex-col"><div class="flex items-center gap-space-xs"><span class="font-title-md text-title-md text-on-surface font-bold">دفعة رقم ${i + 1}</span><span class="inline-flex items-center px-2 py-0.5 rounded-full bg-surface-container text-secondary font-label-sm text-label-sm">${esc(paymentMethod(p.method))}</span></div><span class="font-body-sm text-body-sm text-on-surface-variant mt-0.5">${esc(niceDate(p.createdAt))}</span></div></div><div class="flex flex-col items-end"><div class="font-title-lg text-title-lg font-bold text-secondary">+ ${money(p.amount)}</div><span class="inline-flex items-center gap-0.5 text-secondary font-label-sm text-label-sm mt-0.5"><span class="material-symbols-outlined text-[13px]">check_circle</span>مسجلة</span></div></div>${p.note ? `<div class="bg-surface-container-low rounded-lg px-space-sm py-1.5 flex items-center gap-1.5"><span class="material-symbols-outlined text-[16px] text-on-surface-variant">info</span><span class="font-body-sm text-body-sm text-on-surface-variant">${esc(p.note)}</span></div>` : ''}</div>`).join('') : '<div class="bg-surface-container-lowest rounded-xl p-space-md text-on-surface-variant">لا توجد دفعات على هذا الدين بعد.</div>';
        }
      }
    }
    const shareDetails=()=>share('تفاصيل الدين - سدد', `${person?.name || 'الشخص'}\n${directionLabel(debt.direction)}\nالأصل: ${money(debt.amount)}\nالمسدد: ${money(debt.paid)}\nالمتبقي: ${money(debt.remaining)}\n${debt.note || ''}`);
    $('#shareBtn')?.addEventListener('click', shareDetails);
    $('button[aria-label="محادثة"]')?.addEventListener('click',()=>{if(person)shareStatement(person);});
    $('button[aria-label="خيارات إضافية"]')?.addEventListener('click',()=>showActionMenu('خيارات الدين',[{label:'مشاركة تفاصيل الدين',action:shareDetails},{label:'تسجيل دفعة',action:showPayment},{label:'تعديل بيانات الدين',action:()=>go('debt_edit')}]));
    $$('button').forEach(button => {
      const t = button.textContent.trim();
      if (t.includes('تسجيل دفعة جديدة لهذا الدين')) button.addEventListener('click', showPayment);
      if (t.includes('تعديل بيانات الدين')) button.addEventListener('click', () => go('debt_edit'));
    });
  }
  function paymentMethod(method) { return ({ cash: 'نقداً', bank: 'تحويل بنكي', wallet: 'محفظة رقمية' })[method] || 'دفعة مسجلة'; }

  function initEditDebt() {
    const debt = currentDebt();
    if (!debt) { showMessage('لم يتم العثور على الدين.'); openContacts(); return; }
    selectedDirection = debt.direction;
    $('#amountInput') && ($('#amountInput').value = String(debt.amount));
    $('#debtNotes') && ($('#debtNotes').value = debt.note || '');
    window.selectDirection = direction => setEditDirection(direction);
    function setEditDirection(direction) {
      selectedDirection = direction === 'payable' ? 'payable' : 'receivable';
      const rec = $('#btnReceivable'), pay = $('#btnPayable');
      rec?.classList.toggle('bg-secondary-container', selectedDirection === 'receivable');
      pay?.classList.toggle('bg-secondary-container', selectedDirection === 'payable');
      $('#badgeReceivable')?.classList.toggle('hidden', selectedDirection !== 'receivable');
      $('#badgePayable')?.classList.toggle('hidden', selectedDirection !== 'payable');
      preview();
    }
    function preview() {
      const amount = Number($('#amountInput')?.value || 0);
      const remainder = amount - Number(debt.paid);
      replaceLeaf('500 ₪', money(amount));
      if ($('#previewNewTotal')) $('#previewNewTotal').textContent = money(amount);
      if ($('#previewNewRemaining')) $('#previewNewRemaining').textContent = remainder >= 0 ? money(remainder) : 'غير صالح';
      if ($('#previewDiffBadge')) $('#previewDiffBadge').textContent = `${amount >= debt.amount ? '+' : ''}${money(amount - debt.amount)}`;
      if ($('#previewPctText')) $('#previewPctText').textContent = amount > 0 ? `${fmt(Math.min(100, Math.round(debt.paid / amount * 100)))}% مسدد` : '0%';
      if ($('#previewProgressBar')) $('#previewProgressBar').style.width = amount > 0 ? `${Math.min(100, debt.paid / amount * 100)}%` : '0%';
      if ($('#validationMsg')) $('#validationMsg').textContent = amount < debt.paid ? `لا يمكن أن يقل أصل الدين عن ${money(debt.paid)} وهي قيمة الدفعات المسجلة.` : 'المبلغ صالح للحفظ.';
      if ($('#submitBtn')) $('#submitBtn').disabled = amount < debt.paid || amount <= 0;
    }
    $('#amountInput')?.addEventListener('input', preview);
    $('#btnReceivable')?.addEventListener('click', () => setEditDirection('receivable'));
    $('#btnPayable')?.addEventListener('click', () => setEditDirection('payable'));
    window.openExitDialog = () => $('#exitModal')?.classList.remove('hidden');
    window.closeExitDialog = () => $('#exitModal')?.classList.add('hidden');
    window.confirmExit = () => { window.closeExitDialog(); window.history.back(); };
    window.saveChanges = () => {
      const result = invoke('updateDebt', { debtId: debt.id, amount: Number($('#amountInput')?.value || 0).toFixed(2), direction: selectedDirection, note: $('#debtNotes')?.value.trim() || '', dueDate: debt.dueDate || '' });
      if (!result.ok) { showMessage(result.message || 'تعذر حفظ التعديلات.'); return; }
      showMessage('تم حفظ التعديلات وتحديث الرصيد.');
      setTimeout(() => go('debt'), 250);
    };
    $('#submitBtn')?.addEventListener('click', event => { event.preventDefault(); window.saveChanges(); });
    window.handleAmountChange = preview;
    preview();
  }

  function initLogin() {
    const server = $('#serverUrl');
    try { if (server && window.Sadad) server.value = window.Sadad.getApiBaseUrl(); } catch (_) {}
    $('#saveServerUrl')?.addEventListener('click', () => {
      try { const result = JSON.parse(window.Sadad.setApiBaseUrl(server?.value || '')); $('#serverUrlStatus').textContent = result.ok ? 'تم حفظ عنوان الخادم.' : result.message; }
      catch (_) { $('#serverUrlStatus').textContent = 'تحقق من رابط الخادم.'; }
    });
    $('#loginForm')?.addEventListener('submit', event => {
      event.preventDefault(); const button = $('#loginSubmit'); const error = $('#loginError');
      button.disabled = true; button.textContent = 'جارٍ التحقق…'; if (error) error.textContent = '';
      setTimeout(() => {
        try {
          const result = JSON.parse(window.Sadad.loginWithStaff($('#loginUsername')?.value.trim() || '', $('#loginPassword')?.value || '', $('#loginStaffName')?.value.trim() || ''));
          if (!result.ok) { if (error) error.textContent = result.message || 'تعذر تسجيل الدخول.'; }
        } catch (_) { if (error) error.textContent = 'تعذر الاتصال بالخادم. تحقق من الرابط والإنترنت.'; }
        button.disabled = false; button.textContent = 'دخول آمن';
      }, 40);
    });
  }
  function initChangePassword() {
    $('#changePasswordForm')?.addEventListener('submit', event => {
      event.preventDefault(); const password = $('#newPassword')?.value || ''; const confirm = $('#confirmPassword')?.value || '';
      if (password.length < 12) { $('#passwordError').textContent = 'استخدم 12 محرفاً على الأقل.'; return; }
      if (password !== confirm) { $('#passwordError').textContent = 'كلمتا المرور غير متطابقتين.'; return; }
      try { const result = JSON.parse(window.Sadad.changePassword(password)); if (!result.ok) $('#passwordError').textContent = result.message || 'تعذر تحديث كلمة المرور.'; }
      catch (_) { $('#passwordError').textContent = 'تعذر الاتصال بالخادم.'; }
    });
    $('#logoutFromPassword')?.addEventListener('click', () => window.Sadad.logout());
  }
  function initUnlock() {
    $('#unlockForm')?.addEventListener('submit', event => {
      event.preventDefault();
      try { const result = JSON.parse(window.Sadad.verifyAppPassword($('#appPin')?.value || '')); if (!result.ok) $('#unlockError').textContent = result.message || 'رمز القفل غير صحيح.'; }
      catch (_) { $('#unlockError').textContent = 'تعذر التحقق من رمز القفل.'; }
    });
    $('#useBiometric')?.addEventListener('click', () => window.Sadad.unlockBiometric());
    $('#logoutFromLock')?.addEventListener('click', () => window.Sadad.logout());
  }
  function initWhatsApp() {
    $('#backFromWhatsApp')?.addEventListener('click', () => go('settings'));
    if (!allowed('whatsapp')) { $('#waStatus').textContent = 'ميزة WhatsApp غير مفعّلة من الأدمن.'; $('#connectWhatsApp').disabled = true; return; }
    try {
      const status = JSON.parse(window.Sadad.whatsappStatus());
      if (!status.ok) $('#waStatus').textContent = status.message;
      else if (!status.featureAllowed || !status.enabled) { $('#waStatus').textContent = 'الربط غير متاح الآن. اطلب من أدمن سدد تفعيل WhatsApp لهذا المتجر.'; $('#connectWhatsApp').disabled = true; }
      else $('#waStatus').textContent = status.connected ? `الحساب مربوط. رقم المرسل: ${status.number}.` : 'لم تربط WhatsApp لهذا المتجر بعد.';
      if (status.phoneNumberId) $('#waPhoneId').value = status.phoneNumberId;
      if (status.templateName) $('#waTemplateName').value = status.templateName;
      if (status.templateLanguage) $('#waTemplateLanguage').value = status.templateLanguage;
    } catch (_) { $('#waStatus').textContent = 'تعذر الاتصال بخادم سدد.'; }
    $('#connectWhatsApp')?.addEventListener('click', () => {
      $('#waError').textContent = ''; const token = $('#waToken')?.value || '';
      try { const result = JSON.parse(window.Sadad.connectWhatsApp($('#waPhoneId')?.value || '', token, $('#waTemplateName')?.value || '', $('#waTemplateLanguage')?.value || 'ar')); if (!result.ok) { $('#waError').textContent = result.message; return; } $('#waToken').value = ''; $('#waStatus').textContent = 'تم حفظ الربط المشفر لهذا المتجر.'; showMessage('تم ربط WhatsApp للأعمال.'); }
      catch (_) { $('#waError').textContent = 'تعذر حفظ الربط. تحقق من الخادم والبيانات.'; }
    });
    $('#exportReportsWhatsApp')?.addEventListener('click', () => exportCsv());
  }
  function initReports() {
    if (!allowed('reports')) { showMessage('التقارير غير مفعّلة لحسابك.'); go('home'); return; }
    const cutoff = Date.now() - 30 * 86400000;
    const recent = snapshot.transactions.filter(t => Number(t.createdAt) >= cutoff);
    const debtCount = recent.filter(t => t.kind === 'debt').length;
    const paymentRows = recent.filter(t => t.kind === 'payment');
    const paid = paymentRows.reduce((sum, t) => sum + Number(t.amount), 0);
    const openDebtors = new Set(snapshot.debts.filter(d => Number(d.remaining) > 0).map(d => Number(d.contactId))).size;
    const metrics = [['أشخاص لديهم رصيد مفتوح', openDebtors], ['ديون جديدة خلال 30 يوماً', debtCount], ['دفعات خلال 30 يوماً', paymentRows.length], ['مبالغ مستلمة', money(paid)], ['مستحق لي', money(snapshot.totals?.receivable)], ['مستحق عليّ', money(snapshot.totals?.payable)]];
    const holder = $('#reportMetrics'); if (holder) holder.innerHTML = metrics.map(([name, value]) => `<article class="bg-surface-container-lowest rounded-xl p-space-md shadow-sm"><span class="font-body-sm text-on-surface-variant">${name}</span><b class="block mt-space-xs font-title-lg text-primary">${value}</b></article>`).join('');
    const rows = $('#reportTransactions'); if (rows) rows.innerHTML = recent.slice(0, 30).map(renderCompactTransaction).join('') || '<div class="rounded-xl bg-surface-container p-space-md text-on-surface-variant">لا توجد حركات خلال آخر 30 يوماً.</div>';
    $$('[data-tx-debt]', rows || document).forEach(row => row.addEventListener('click', () => openDebt(row.dataset.txDebt)));
    if ($('#reportCount')) $('#reportCount').textContent = `${fmt(recent.length)} حركة`;
    if ($('#reportFootnote')) $('#reportFootnote').textContent = `تم إنشاء الملخص في ${niceDate(Date.now())}. يُبنى على قيود هذا المتجر المحفوظة.`;
    $('#exportReport')?.addEventListener('click', exportCsv);
  }
  function setPasswordHint() { if ($('#unlockHint') && !session.hasAppPassword) $('#unlockHint').textContent = 'قفل التطبيق غير مضبوط. سجّل الدخول بكلمة مرور المتجر.'; }
  window.openEntryFlow = flow => flow === 'payment' ? showPayment() : showAddDebt();
  window.triggerFeedback = element => {
    const person = currentContact(); const label = element?.getAttribute('aria-label') || element?.textContent || '';
    if (person && /مشاركة|share/i.test(label)) { shareStatement(person); return; }
    const note = window.prompt('اكتب ملاحظتك لفريق سدد:'); if (note?.trim()) share('ملاحظة لتطبيق سدد', note.trim());
  };
  window.triggerFriendlyReminder = element => {
    const person = snapshot.contacts.find(c => (element?.parentElement?.textContent || '').includes(c.name)) || currentContact();
    if (person) share('تذكير ودي — سدد', `مرحباً ${person.name}، هذا تذكير ودي بخصوص كشف حسابنا. يمكنك التواصل معي لمراجعته. — سدد`);
    else showMessage('أضف جهة اتصال لتتمكن من إرسال تذكير.');
  };
  window.hideToast = () => { const toast = $('#sadad-toast'); if (toast) toast.style.opacity = '0'; };

  function initSettings() {
    $('#logout-modal')?.classList.add('hidden');
    const profileEdit = $$('button').find(button => button.textContent.includes('تعديل الملف'));
    profileEdit?.addEventListener('click', () => showInfoDialog('بيانات المتجر', 'اسم المتجر وحالة التوثيق تتم إدارتهما من لوحة إدارة سدد.'));
    const fingerprintLabel = findLeaf('بصمة الوجه / الرمز السري');
    fingerprintLabel?.parentElement?.parentElement?.parentElement?.parentElement?.remove();
    const accountName = session.account?.name || 'حساب المتجر';
    replaceLeaf('عمر الحلبي', accountName);
    replaceLeaf('+970 59 123 4567', session.account?.username || '');
    replaceLeaf('عضو منذ يناير ٢٠٢٤', session.account?.verified ? 'حساب موثّق من إدارة سدد' : 'حساب متجر تابع لمنصة سدد');
    replaceLeaf('آخر مزامنة سحابية: اليوم، 11:30 ص', 'يتم حفظ نسخة احتياطية على خادم سدد عند توفر الاتصال');
    replaceLeaf('تأكيد تسجيل الخروج', 'تسجيل الخروج من التطبيق');
    replaceLeaf('هل أنت متأكد من رغبتك في تسجيل الخروج؟ بياناتك المالية والتزاماتك محفوظة ومحمية بأمان على سحابة سدد المشفرة.', 'سيتم إنهاء جلسة هذا الجهاز. تبقى بيانات المتجر على خادم سدد وفق صلاحيات الأدمن.');
    const logoutText = findLeaf('تسجيل الخروج'); if (logoutText) logoutText.nodeValue = 'تسجيل الخروج';
    const content = $('main > div');
    if (content && !$('#settingsControls')) {
      const card = document.createElement('section'); card.id = 'settingsControls';
      card.className = 'bg-surface-container-lowest rounded-2xl shadow-sm p-space-md flex flex-col gap-space-sm';
      const dark = document.documentElement.dataset.theme === 'dark';
      card.innerHTML = `<div class="flex items-center justify-between"><div class="flex flex-col"><span class="font-title-md text-title-md text-on-surface font-bold">المظهر</span><span class="font-body-sm text-body-sm text-on-surface-variant">اختر ألوان سدد الفاتحة أو الداكنة</span></div><div class="flex gap-1"><button data-theme-choice="light" class="h-10 px-3 rounded-lg ${dark ? 'bg-surface-container text-on-surface' : 'bg-secondary text-on-secondary'} font-label-md text-label-md">فاتح</button><button data-theme-choice="dark" class="h-10 px-3 rounded-lg ${dark ? 'bg-secondary text-on-secondary' : 'bg-surface-container text-on-surface'} font-label-md text-label-md">داكن</button></div></div><div class="h-px bg-surface-container-highest"></div><div class="flex flex-col gap-space-xs"><span class="font-title-md text-title-md text-on-surface font-bold">قفل التطبيق</span><span class="font-body-sm text-body-sm text-on-surface-variant">رمز محلي مشفر ببصمة اشتقاق؛ وتحقق البصمة/الوجه عبر شاشة Android الرسمية.</span><div class="flex gap-space-xs"><input id="newAppPin" class="min-w-0 flex-1 h-11 rounded-lg bg-surface-container-low px-space-sm" type="password" inputmode="numeric" maxlength="12" placeholder="رمز جديد من 4 إلى 12 رقمًا"><button id="saveAppPin" class="px-space-md rounded-lg bg-primary-container text-on-primary font-label-md text-label-md">حفظ الرمز</button></div><label class="flex items-center gap-space-sm py-1"><input id="appLockToggle" type="checkbox" class="w-5 h-5 accent-secondary" ${session.hasAppPassword ? 'checked' : ''}><span class="font-body-sm text-on-surface">قفل التطبيق عند مغادرته</span></label><label class="flex items-center gap-space-sm py-1"><input id="biometricToggle" type="checkbox" class="w-5 h-5 accent-secondary" ${session.biometricEnabled ? 'checked' : ''}><span class="font-body-sm text-on-surface">البصمة أو التعرّف على الوجه</span></label><button id="openWhatsAppSettings" class="h-11 rounded-lg bg-secondary-container text-on-secondary-container font-title-md text-title-md flex items-center justify-center gap-space-xs"><span class="material-symbols-outlined">chat</span>ربط WhatsApp وكشوف الحساب</button><button id="openReportsSettings" class="h-10 rounded-lg bg-surface-container text-primary font-title-md text-title-md">عرض التقارير والإحصائيات</button></div>`;
      const reference = content.children[1] || null; content.insertBefore(card, reference);
      const reminders = $('#automaticReminderToggle');
      if (reminders) { reminders.checked = store.get('reminders', 'on') !== 'off'; reminders.addEventListener('change', () => { store.set('reminders', reminders.checked ? 'on' : 'off'); showMessage(reminders.checked ? 'تم حفظ تفضيل التذكيرات على هذا الجهاز.' : 'تم إيقاف تفضيل التذكيرات.'); }); }
      const leadDays = store.get('reminderLeadDays', '2');
      const leadText = ({'1':'قبل يوم','2':'قبل يومين','7':'قبل أسبوع'})[leadDays] || 'قبل يومين';
      const leadLabel = $('#reminderLeadTime .reminder-lead-value'); if (leadLabel) leadLabel.textContent = leadText;
      $('#reminderLeadTime')?.addEventListener('click', () => {
        const choice = window.prompt('اختر المهلة: اكتب 1 قبل يوم، 2 قبل يومين، أو 7 قبل أسبوع.', store.get('reminderLeadDays', '2'));
        if (choice == null) return;
        const value = choice.trim(); if (!['1','2','7'].includes(value)) { showMessage('اكتب 1 أو 2 أو 7.'); return; }
        store.set('reminderLeadDays', value);
        const target = $('#reminderLeadTime .reminder-lead-value'); if (target) target.textContent = ({'1':'قبل يوم','2':'قبل يومين','7':'قبل أسبوع'})[value];
        showMessage('تم حفظ مهلة التنبيه على هذا الجهاز.');
      });
      $$('[data-theme-choice]', card).forEach(button => button.addEventListener('click', () => setTheme(button.dataset.themeChoice)));
      $('#saveAppPin')?.addEventListener('click', () => {
        if (!allowed('appLock')) { showMessage('قفل التطبيق غير مفعّل من الأدمن.'); return; }
        const result = invoke('setAppPassword', { pin: $('#newAppPin')?.value || '' });
        if (!result.ok) { showMessage(result.message); return; }
        session.hasAppPassword = true; $('#appLockToggle').checked = true; $('#newAppPin').value = ''; showMessage('تم حفظ رمز قفل التطبيق على هذا الجهاز.');
      });
      $('#appLockToggle')?.addEventListener('change', event => {
        if (!allowed('appLock')) { event.target.checked = false; showMessage('إعدادات القفل يتحكم بها الأدمن.'); return; }
        if (event.target.checked && !session.hasAppPassword) { event.target.checked = false; showMessage('أنشئ رمز قفل أولاً.'); return; }
        try { window.Sadad.setAppLockEnabled(event.target.checked); if (!event.target.checked) { window.Sadad.disableBiometric(); const biometric = $('#biometricToggle'); if (biometric) biometric.checked = false; session.biometricEnabled = false; } } catch (_) {}
      });
      $('#biometricToggle')?.addEventListener('change', event => {
        if (!allowed('appLock')) { event.target.checked = false; showMessage('إعدادات القفل يتحكم بها الأدمن.'); return; }
        if (event.target.checked) window.Sadad.enableBiometric(); else window.Sadad.disableBiometric();
      });
      window.onBiometricSetting = enabled => { const toggle = $('#biometricToggle'); if (toggle) toggle.checked = !!enabled; session.biometricEnabled = !!enabled; };
      $('#openWhatsAppSettings')?.addEventListener('click', () => go('whatsapp'));
      $('#openReportsSettings')?.addEventListener('click', () => go('reports'));
    }
    $('#trigger-logout-btn')?.addEventListener('click', () => $('#logout-modal')?.classList.remove('hidden'));
    $('#cancel-logout-btn')?.addEventListener('click', () => $('#logout-modal')?.classList.add('hidden'));
    $('#confirm-logout-btn')?.addEventListener('click', () => {
      $('#logout-modal')?.classList.add('hidden');
      try { window.Sadad.logout(); } catch (_) { go('login'); }
    });
    $$('button').forEach(button => {
      if (/^تصدير$/.test(button.textContent.trim()) && !button.dataset.exportBound) { button.dataset.exportBound = '1'; button.addEventListener('click', () => exportCsv()); }
    });
    $$('.cursor-pointer').forEach(row => row.addEventListener('click', () => {
      const text = row.textContent.trim();
      if (/العملة الافتراضية/.test(text)) showInfoDialog('العملة', 'العملة المعتمدة حالياً في التطبيق هي الشيكل الفلسطيني (₪).');
      else if (/النسخ الاحتياطي والمزامنة/.test(text)) { try { window.Sadad.syncNow(); } catch (_) { showMessage('تعذرت المزامنة الآن.'); } }
      else if (/كشف حساب وتصدير التقرير/.test(text)) exportCsv();
      else if (/واتساب|WhatsApp/i.test(text)) go('whatsapp');
      else if (/الأسئلة الشائعة/.test(text)) showInfoDialog('الأسئلة الشائعة', 'أضف جهة اتصال، ثم سجّل الدين أو الدفعة. جميع الأرصدة تُحسب من القيود المحفوظة. تُرسل كشوف WhatsApp عند طلبك وبموافقة المستلم.');
      else if (/فريق الدعم المباشر/.test(text)) showInfoDialog('الدعم', 'تواصل مع الأدمن الذي أنشأ حساب متجرك للحصول على المساعدة.');
      else if (/نغمة التنبيهات/.test(text)) { const next = store.get('sound', 'on') !== 'off'; store.set('sound', next ? 'off' : 'on'); showMessage(next ? 'تم إيقاف نغمة التنبيه.' : 'تم تفعيل نغمة التنبيه.'); }
      else if (/الإشعارات/.test(text)) { const next = store.get('notifications', 'on') !== 'off'; store.set('notifications', next ? 'off' : 'on'); showMessage(next ? 'تم إيقاف التنبيهات.' : 'تم تفعيل التنبيهات.'); }
      else if (/النسخ الاحتياطي والمزامنة|التقارير والتصدير/.test(text)) exportCsv();
    }));
  }
  function setTheme(theme) {
    const selected = theme === 'dark' ? 'dark' : 'light';
    document.documentElement.dataset.theme = selected; store.set('theme', selected);
    try { window.Sadad.setTheme(selected); } catch (_) {}
    refreshBranding();
    $$('[data-theme-choice]').forEach(button => {
      const on = button.dataset.themeChoice === selected;
      button.classList.toggle('bg-secondary', on); button.classList.toggle('text-on-secondary', on);
      button.classList.toggle('bg-surface-container', !on); button.classList.toggle('text-on-surface', !on);
    });
  }
  function showInfoDialog(title, body) {
    const modal = document.createElement('div'); modal.className = 'fixed inset-0 z-50 flex items-center justify-center p-space-md bg-inverse-surface/50';
    modal.innerHTML = `<section class="w-full max-w-sm rounded-2xl bg-surface-container-lowest p-space-lg shadow-xl"><div class="flex items-center gap-space-sm mb-space-sm"><img src="${document.documentElement.dataset.theme === 'dark' ? '../logo-dark.png' : '../logo-light.jpeg'}" class="w-10 h-10 rounded-lg" alt=""><h2 class="font-title-lg text-title-lg text-primary">${esc(title)}</h2></div><p class="font-body-md text-body-md text-on-surface-variant">${esc(body)}</p><button class="mt-space-md w-full h-11 rounded-xl bg-primary-container text-on-primary font-title-md text-title-md">إغلاق</button></section>`;
    modal.addEventListener('click', event => { if (event.target === modal || event.target.closest('button')) modal.remove(); }); document.body.appendChild(modal);
  }
  function showActionMenu(title, actions) {
    const modal=document.createElement('div'); modal.className='fixed inset-0 z-50 flex items-end sm:items-center justify-center p-space-md bg-inverse-surface/50 backdrop-blur-sm';
    modal.innerHTML=`<section role="dialog" aria-modal="true" class="w-full max-w-sm rounded-2xl bg-surface-container-lowest p-space-md shadow-2xl"><div class="flex items-center justify-between mb-space-sm"><h2 class="font-title-lg text-title-lg text-primary">${esc(title)}</h2><button data-menu-close class="w-10 h-10 rounded-full text-primary" aria-label="إغلاق">✕</button></div><div class="flex flex-col gap-space-xs">${actions.map((item,index)=>`<button data-menu-action="${index}" class="w-full h-12 rounded-xl bg-surface-container text-on-surface text-right px-space-md font-title-md text-title-md">${esc(item.label)}</button>`).join('')}</div></section>`;
    modal.addEventListener('click',event=>{if(event.target===modal||event.target.closest('[data-menu-close]')){modal.remove();return;}const button=event.target.closest('[data-menu-action]');if(button){const action=actions[Number(button.dataset.menuAction)]?.action;modal.remove();if(action)action();}});
    document.body.appendChild(modal);
  }
  function exportCsv() {
    const rows = [['النوع','الشخص','الاتجاه','المبلغ','طريقة الدفع','التاريخ','البيان']];
    snapshot.transactions.forEach(tx => rows.push([tx.kind === 'payment' ? 'دفعة' : 'دين', tx.contactName, directionLabel(tx.direction), tx.amount, paymentMethod(tx.method), niceDate(tx.createdAt), tx.note || '']));
    const csv = '\uFEFF' + rows.map(row => row.map(v => `"${String(v).replace(/"/g, '""')}"`).join(',')).join('\r\n');
    share('كشف حساب سدد - CSV', csv);
  }

  function initNavOnly() {
    $$('nav a[data-path]').forEach(link => link.addEventListener('click', event => {
      event.preventDefault();
      if (link.dataset.path === 'contacts' || link.dataset.path === 'people') openContacts();
      else if (link.dataset.path === 'history' || link.dataset.path === 'ledger' || link.dataset.path === 'reports') go('reports');
      else go(link.dataset.path === 'home' ? 'home' : link.dataset.path);
    }));
  }

  function showNotificationCenter() {
    if (store.get('reminders', 'on') === 'off') { showMessage('التذكيرات متوقفة من الإعدادات.'); return; }
    const lead = Number(store.get('reminderLeadDays', '2')) || 2;
    const now = new Date(); now.setHours(0,0,0,0);
    const limit = new Date(now); limit.setDate(limit.getDate() + lead);
    const due = snapshot.debts.filter(debt => {
      if (!debt.dueDate || Number(debt.remaining) <= 0) return false;
      const date = new Date(`${debt.dueDate}T00:00:00`);
      return !Number.isNaN(date.getTime()) && date <= limit;
    }).sort((a,b) => String(a.dueDate).localeCompare(String(b.dueDate)));
    const modal = document.createElement('div');
    modal.className = 'fixed inset-0 z-50 flex items-end sm:items-center justify-center p-space-md bg-inverse-surface/50 backdrop-blur-sm';
    const rows = due.map(debt => {
      const person = snapshot.contacts.find(contact => Number(contact.id) === Number(debt.contactId));
      const date = new Date(`${debt.dueDate}T00:00:00`);
      const late = date < now;
      return `<button data-reminder-debt="${Number(debt.id)}" class="w-full text-right rounded-xl p-space-md bg-surface-container-lowest flex items-center justify-between gap-space-sm"><span><b class="block text-title-md text-primary">${esc(person?.name || 'جهة اتصال')}</b><small class="text-body-sm text-on-surface-variant">${late ? 'متأخر منذ' : 'موعد الاستحقاق'} ${esc(date.toLocaleDateString('ar-PS'))}</small></span><b class="text-label-lg ${late ? 'text-error' : 'text-secondary'}">${money(debt.remaining)}</b></button>`;
    }).join('');
    modal.innerHTML = `<section role="dialog" aria-modal="true" class="w-full max-w-md max-h-[85vh] overflow-y-auto rounded-2xl bg-surface-container-lowest p-space-md shadow-2xl"><div class="flex items-center justify-between gap-space-sm mb-space-sm"><div class="flex items-center gap-space-sm"><img src="${document.documentElement.dataset.theme === 'dark' ? '../logo-dark.png' : '../logo-light.jpeg'}" class="w-10 h-10 rounded-lg" alt=""><div><h2 class="font-title-lg text-title-lg text-primary">مركز الاستحقاقات</h2><p class="font-body-sm text-on-surface-variant">الديون المستحقة أو القريبة خلال ${lead} أيام</p></div></div><button data-close-reminders class="w-10 h-10 rounded-full text-primary" aria-label="إغلاق">✕</button></div><div class="flex flex-col gap-space-xs">${rows || '<p class="rounded-xl bg-surface-container p-space-md text-body-md text-on-surface-variant">لا توجد استحقاقات قريبة مسجلة.</p>'}</div></section>`;
    modal.addEventListener('click', event => {
      if (event.target === modal || event.target.closest('[data-close-reminders]')) { modal.remove(); return; }
      const item = event.target.closest('[data-reminder-debt]'); if (item) { const id = item.dataset.reminderDebt; modal.remove(); openDebt(id); }
    });
    document.body.appendChild(modal);
  }
  function bindNotificationButtons() {
    $$('button[aria-label="الإشعارات"],button[aria-label="التنبيهات"]').forEach(button => button.addEventListener('click', showNotificationCenter));
  }

  function init() {
    refreshBranding();
    bindNotificationButtons();
    if (screen === 'login') { initLogin(); return; }
    if (!session.authenticated) { go('login'); return; }
    if (session.forcePasswordChange && screen !== 'change_password') { go('change_password'); return; }
    if (screen === 'change_password') { initChangePassword(); return; }
    if (screen === 'unlock') { initUnlock(); setPasswordHint(); return; }
    if (screen === 'whatsapp') { activeNav(); initWhatsApp(); return; }
    if (screen === 'reports') { activeNav(); initReports(); return; }
    activeNav();
    if (screen === 'home') initHome();
    else if (screen === 'contacts' || screen === 'contacts_empty') {
      if (screen === 'contacts_empty' && snapshot.contacts.length) { go('contacts'); return; }
      if (screen === 'contacts_empty') {
        $$('#emptyContactFilters button').forEach(button => { button.disabled = true; });
        $('#add-first-contact-btn')?.addEventListener('click', () => go('contact_add'));
      } else initContacts();
      $('#addNewContactBtn')?.addEventListener('click', () => go('contact_add'));
    } else if (screen === 'contact_add') initAddContact();
    else if (screen === 'contact' || screen === 'contact_empty') {
      if (screen === 'contact' && personTransactions(contactId).length === 0) { go('contact_empty'); return; }
      if (screen === 'contact_empty' && personTransactions(contactId).length > 0) { go('contact'); return; }
      initContactDetails();
      $$('button').forEach(button => {
        const text = button.textContent.trim();
        if (text.includes('إضافة أول دين') || text.includes('إضافة دين')) button.addEventListener('click', showAddDebt);
        if (text.includes('تسجيل دفعة')) button.addEventListener('click', showPayment);
        if (text.includes('مشاركة الحساب')) button.addEventListener('click', () => { const person = currentContact(); if (person) shareStatement(person); });
      });
    } else if (screen === 'debt_add') initAddDebt();
    else if (screen === 'payment') initPayment();
    else if (screen === 'history') initHistory();
    else if (screen === 'debt') initDebtDetails();
    else if (screen === 'debt_edit') initEditDebt();
    else if (screen === 'settings') initSettings();
    else initNavOnly();
    if (!allowed('debts')) $$('#add-debt-btn,#openAddDebt,#saveDebtBtn').forEach(button => button.disabled = true);
    if (!allowed('payments')) $$('#record-pay-btn,#openRecordPayment,#confirm-submit-btn').forEach(button => button.disabled = true);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
