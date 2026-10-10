import assert from 'node:assert/strict';
import { PGlite } from '../../.local-tools/node_modules/@electric-sql/pglite/dist/index.js';

// نموذج مستقل لصلاحيات PostgreSQL، لا يتصل بالمشروع المنشور.
const db = new PGlite();
const results = [];
async function check(name, fn) { await fn(); results.push({ name, status: 'passed' }); }
async function denied(sql) {
  await assert.rejects(db.exec(sql), /permission denied|row-level security|authorization_required/);
}
try {
  await db.exec(`
    create role authenticated nologin;
    create role anon nologin;
    create role ledger_executor nologin nosuperuser nobypassrls;
    create schema private;
    revoke all on schema private from public;
    grant usage on schema private to authenticated, ledger_executor;
    create table private.authorizations (
      user_id text, session_id text, store_id bigint, ticket text,
      active boolean not null, expires_at timestamptz not null,
      primary key(user_id,session_id)
    );
    insert into private.authorizations values
      ('user-a','session-a',1,'opaque-proof-a',true,now()+interval '1 minute'),
      ('user-b','session-b',2,'opaque-proof-b',true,now()+interval '1 minute');
    grant select on private.authorizations to ledger_executor;
    create function private.authorized_store() returns bigint
    language sql stable security definer set search_path='' as $$
      select store_id from private.authorizations
      where user_id=current_setting('request.jwt.claims',true)::jsonb->>'sub'
        and session_id=current_setting('request.jwt.claims',true)::jsonb->>'session_id'
        and ticket=current_setting('request.headers',true)::jsonb->>'x-sadad-capability'
        and active and expires_at>now()
    $$;
    alter function private.authorized_store() owner to ledger_executor;
    revoke all on function private.authorized_store() from public;
    grant execute on function private.authorized_store() to authenticated,ledger_executor;
    create table public.financial_entries (store_id bigint, id text primary key, cents bigint check(cents>0));
    alter table public.financial_entries enable row level security;
    alter table public.financial_entries force row level security;
    revoke all on public.financial_entries from public;
    grant select on public.financial_entries to authenticated;
    grant select,insert on public.financial_entries to ledger_executor;
    create policy scoped_read on public.financial_entries for select to authenticated,ledger_executor
      using(store_id=(select private.authorized_store()));
    create policy scoped_write on public.financial_entries for insert to ledger_executor
      with check(store_id=(select private.authorized_store()));
    create function private.apply(p_store bigint,p_id text,p_cents bigint) returns void
    language plpgsql security definer set search_path='' as $$
    begin
      if private.authorized_store() is null then raise exception 'authorization_required'; end if;
      insert into public.financial_entries values(p_store,p_id,p_cents);
    end $$;
    alter function private.apply(bigint,text,bigint) owner to ledger_executor;
    revoke all on function private.apply(bigint,text,bigint) from public;
    grant execute on function private.apply(bigint,text,bigint) to authenticated;
    create function public.apply(p_store bigint,p_id text,p_cents bigint) returns void
    language sql security invoker set search_path='' as $$ select private.apply(p_store,p_id,p_cents) $$;
    revoke all on function public.apply(bigint,text,bigint) from public;
    grant execute on function public.apply(bigint,text,bigint) to authenticated;
    set role authenticated;
    select set_config('request.jwt.claims','{"sub":"user-a","session_id":"session-a"}',false);
    select set_config('request.headers','{"x-sadad-capability":"opaque-proof-a"}',false);
  `);
  await check('رفض الإدخال المباشر', () => denied("insert into public.financial_entries values(1,'direct',100)"));
  await check('قبول الدالة ضمن نطاق المتجر', () => db.exec("select public.apply(1,'accepted',100)"));
  await check('رفض نطاق متجر آخر بواسطة RLS داخل الدالة', () => denied("select public.apply(2,'foreign',100)"));
  await check('رفض تعديل وحذف التاريخ المباشر', async () => {
    await denied("update public.financial_entries set cents=1");
    await denied("delete from public.financial_entries");
  });
  await check('رفض انتحال سياق المتجر بإعداد محلي', async () => {
    await db.exec("select set_config('sadad.store_id','2',false)");
    await denied("select public.apply(2,'spoofed',100)");
  });
  await check('رفض تذكرة مزورة', async () => {
    await db.exec(`select set_config('request.headers','{"x-sadad-capability":"forged"}',false)`);
    await denied("select public.apply(1,'forged',100)");
    assert.equal((await db.query('select * from public.financial_entries')).rows.length,0);
  });
  await check('رفض جلسة مستخدم مختلف مع تذكرة مسروقة', async () => {
    await db.exec(`select set_config('request.jwt.claims','{"sub":"user-b","session_id":"session-b"}',false);
      select set_config('request.headers','{"x-sadad-capability":"opaque-proof-a"}',false)`);
    await denied("select public.apply(1,'stolen',100)");
  });
  await check('عزل قراءة متجر آخر', async () => {
    await db.exec(`select set_config('request.headers','{"x-sadad-capability":"opaque-proof-b"}',false)`);
    assert.equal((await db.query('select * from public.financial_entries')).rows.length,0);
  });
  await check('رفض الدور غير المسجل', async () => {
    await db.exec('reset role; set role anon');
    await denied("select public.apply(1,'anonymous',100)");
  });
  await check('إلغاء التفويض يمنع القراءة والكتابة فورًا', async () => {
    await db.exec(`reset role; update private.authorizations set active=false where user_id='user-a';
      set role authenticated;
      select set_config('request.jwt.claims','{"sub":"user-a","session_id":"session-a"}',false);
      select set_config('request.headers','{"x-sadad-capability":"opaque-proof-a"}',false)`);
    assert.equal((await db.query('select * from public.financial_entries')).rows.length,0);
    await denied("select public.apply(1,'revoked',100)");
  });
  await db.exec('reset role');
  const roles = (await db.query("select rolname,rolsuper,rolbypassrls from pg_roles where rolname='ledger_executor'")).rows;
  assert.equal(roles[0].rolsuper,false); assert.equal(roles[0].rolbypassrls,false);
  console.log(JSON.stringify({ engine: (await db.query('select version()')).rows[0].version, results, roles },null,2));
} finally { await db.close(); }
