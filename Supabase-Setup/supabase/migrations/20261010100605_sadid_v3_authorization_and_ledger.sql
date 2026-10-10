-- مخطط جديد مستقل؛ لا يطبق ملف ledger_foundation القديم ولا يمس السجل السابق.
create schema sadid_private;
revoke all on schema sadid_private from public,anon,authenticated,service_role;
create role sadid_ledger_executor nologin nosuperuser nobypassrls noinherit;
create role sadid_auth_reader nologin nosuperuser nobypassrls noinherit;
grant sadid_ledger_executor,sadid_auth_reader to postgres;
grant usage on schema public,sadid_private,auth to sadid_ledger_executor,sadid_auth_reader;
grant create on schema sadid_private to sadid_ledger_executor,sadid_auth_reader;
grant usage on schema sadid_private to authenticated,service_role;

create table sadid_private.store_memberships (
 user_id uuid primary key references auth.users(id) on delete restrict,
 store_id bigint not null unique references public.stores(id) on delete restrict,
 created_at timestamptz not null default now()
);
create table sadid_private.installations (
 id uuid primary key, store_id bigint not null references public.stores(id) on delete restrict,
 generation integer not null check(generation>0), public_key jsonb not null,
 status text not null check(status in ('pending','active','revoked')),
 approved_by text, approved_at timestamptz, revoked_at timestamptz, reason text,
 created_at timestamptz not null default now(), unique(store_id,id,generation)
);
create unique index sadid_one_active_installation on sadid_private.installations(store_id) where status='active';
create table sadid_private.installation_sessions (
 auth_session_id uuid primary key references auth.sessions(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete restrict,
 store_id bigint not null, installation_id uuid not null,generation integer not null,
 foreign key(store_id,installation_id,generation) references sadid_private.installations(store_id,id,generation)
);
create table sadid_private.capabilities (
 token_hash bytea primary key, user_id uuid not null, auth_session_id uuid not null,
 store_id bigint not null, installation_id uuid not null,generation integer not null,
 scope text not null check(scope in ('read','write','outcomes','lease')),
 request jsonb, issued_at timestamptz not null default clock_timestamp(),
 expires_at timestamptz not null, foreign key(store_id,installation_id,generation)
 references sadid_private.installations(store_id,id,generation)
);
create index sadid_capability_expiry on sadid_private.capabilities(expires_at);
create table sadid_private.request_nonces (
 installation_id uuid not null references sadid_private.installations(id),
 nonce uuid not null, created_at timestamptz not null default now(), primary key(installation_id,nonce)
);
create table sadid_private.control_events (
 id bigint generated always as identity primary key, store_id bigint not null,
 actor text not null, kind text not null, reason text not null,
 details jsonb not null default '{}', created_at timestamptz not null default clock_timestamp()
);

create table public.sadid_ledger_state (
 store_id bigint primary key references public.stores(id) on delete restrict,
 epoch uuid not null default gen_random_uuid(), cursor bigint not null default 0 check(cursor>=0),
 currency text not null default 'ILS' check(currency='ILS'), schema_version integer not null default 1
);
create table public.sadid_contacts (
 store_id bigint not null references public.sadid_ledger_state(store_id) on delete restrict,
 id uuid not null,name text not null check(length(trim(name)) between 1 and 120),
 phone text not null default '',category text not null default '',note text not null default '',
 external_reference text not null default '',archived boolean not null default false,
 version bigint not null default 1,operation_id uuid not null,created_at timestamptz not null,
 primary key(store_id,id)
);
create unique index sadid_unique_phone on public.sadid_contacts(store_id,phone) where phone<>'';
create table public.sadid_debts (
 store_id bigint not null,id uuid not null,contact_id uuid not null,
 direction text not null check(direction in ('receivable','payable')),
 amount_cents bigint not null check(amount_cents between 1 and 9007199254740991),
 note text not null default '', due_date date, operation_id uuid not null,created_at timestamptz not null,
 primary key(store_id,id),foreign key(store_id,contact_id) references public.sadid_contacts(store_id,id)
);
create table public.sadid_payments (
 store_id bigint not null,id uuid not null,debt_id uuid not null,
 amount_cents bigint not null check(amount_cents between 1 and 9007199254740991),
 method text not null check(method in ('cash','bank','wallet')),note text not null default '',
 operation_id uuid not null,created_at timestamptz not null,
 primary key(store_id,id),foreign key(store_id,debt_id) references public.sadid_debts(store_id,id)
);
create table public.sadid_adjustments (
 store_id bigint not null,id uuid not null,debt_id uuid not null,amount_cents bigint not null check(amount_cents between 1 and 9007199254740991),
 previous_amount_cents bigint not null,version bigint not null,reason text not null check(length(trim(reason)) between 1 and 2000),
 operation_id uuid not null,created_at timestamptz not null default clock_timestamp(),
 primary key(store_id,id),unique(store_id,debt_id,version),foreign key(store_id,debt_id) references public.sadid_debts(store_id,id)
);
create table public.sadid_reversals (
 store_id bigint not null,id uuid not null,payment_id uuid not null,
 reason text not null check(length(trim(reason)) between 1 and 2000),operation_id uuid not null,
 created_at timestamptz not null default clock_timestamp(),primary key(store_id,id),unique(store_id,payment_id),
 foreign key(store_id,payment_id) references public.sadid_payments(store_id,id)
);
create table public.sadid_operations (
 store_id bigint not null references public.sadid_ledger_state(store_id),operation_id uuid not null,
 request jsonb not null,result jsonb not null,user_id uuid not null,installation_id uuid not null,
 generation integer not null,received_at timestamptz not null default clock_timestamp(),primary key(store_id,operation_id)
);
create table public.sadid_events (
 store_id bigint not null references public.sadid_ledger_state(store_id),sequence bigint not null,
 operation_id uuid not null,kind text not null,entity_id uuid not null,user_id uuid not null,
 installation_id uuid not null,generation integer not null,command jsonb not null,
 created_at timestamptz not null default clock_timestamp(),primary key(store_id,sequence),
 foreign key(store_id,operation_id) references public.sadid_operations(store_id,operation_id) deferrable initially deferred
);

-- فحص ضيق لجلسات Auth المحمية؛ لا يقرأ أو يكتب أية سجلات مالية.
create function sadid_private.auth_session_active(p_user uuid,p_session uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select exists(select 1 from auth.sessions where id=p_session and user_id=p_user and (not_after is null or not_after>statement_timestamp()))
$$;
revoke all on function sadid_private.auth_session_active(uuid,uuid) from public,anon,authenticated,service_role;
grant execute on function sadid_private.auth_session_active(uuid,uuid) to sadid_auth_reader;
create function sadid_private.current_capability() returns sadid_private.capabilities
language sql stable security definer set search_path='' as $$
 select c from sadid_private.capabilities c
 join sadid_private.store_memberships m on m.user_id=c.user_id and m.store_id=c.store_id
 join sadid_private.installations i on i.id=c.installation_id and i.store_id=c.store_id and i.generation=c.generation and i.status='active'
 join sadid_private.installation_sessions b on b.auth_session_id=c.auth_session_id and b.user_id=c.user_id and b.installation_id=c.installation_id and b.generation=c.generation
 join public.stores s on s.id=c.store_id and s.status='active' and not s.force_password_change
 where c.token_hash=pg_catalog.sha256(pg_catalog.convert_to(coalesce(nullif(current_setting('request.headers',true),'')::jsonb->>'x-sadad-capability',''),'UTF8'))
 and c.user_id=(nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid
 and c.auth_session_id=(nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'session_id')::uuid
 and c.expires_at>statement_timestamp()
 and sadid_private.auth_session_active(c.user_id,c.auth_session_id)
 limit 1
$$;
alter function sadid_private.current_capability() owner to sadid_auth_reader;
create function sadid_private.authorized_store() returns bigint
language sql stable security invoker set search_path='' as $$ select (sadid_private.current_capability()).store_id $$;
revoke all on function sadid_private.current_capability(),sadid_private.authorized_store() from public,anon,service_role;
grant execute on function sadid_private.current_capability(),sadid_private.authorized_store() to authenticated,sadid_ledger_executor;
grant select on sadid_private.store_memberships,sadid_private.installations,sadid_private.installation_sessions,sadid_private.capabilities to sadid_auth_reader;
grant select on public.stores to sadid_auth_reader;
-- جداول التحكم الخاصة لها سياسات صريحة للأدوار الداخلية فقط.
do $$ declare t text; begin
 foreach t in array array['store_memberships','installations','installation_sessions','capabilities','request_nonces','control_events'] loop
  execute format('alter table sadid_private.%I enable row level security',t);
  execute format('grant select on sadid_private.%I to sadid_auth_reader',t);
  execute format('create policy internal_reader on sadid_private.%I for select to sadid_auth_reader using(true)',t);
 end loop;
end $$;
-- ملكية public.stores القديمة ليست ضمن ملكية المنفذ؛ منح قراءة مقيدة للتحقق فقط.
create policy sadid_auth_store_check on public.stores for select to sadid_auth_reader using(true);

do $$ declare t text; begin
 foreach t in array array['sadid_ledger_state','sadid_contacts','sadid_debts','sadid_payments','sadid_adjustments','sadid_reversals','sadid_operations','sadid_events'] loop
  execute format('alter table public.%I enable row level security',t);
  execute format('alter table public.%I force row level security',t);
  execute format('revoke all on public.%I from public,anon,authenticated,service_role',t);
  execute format('grant select on public.%I to authenticated,sadid_ledger_executor',t);
  execute format('grant insert on public.%I to sadid_ledger_executor',t);
  execute format('create policy store_read on public.%I for select to authenticated,sadid_ledger_executor using(store_id=(select sadid_private.authorized_store()))',t);
  execute format('create policy store_insert on public.%I for insert to sadid_ledger_executor with check(store_id=(select sadid_private.authorized_store()))',t);
 end loop;
 foreach t in array array['sadid_ledger_state','sadid_contacts'] loop
  execute format('grant update on public.%I to sadid_ledger_executor',t);
  execute format('create policy store_update on public.%I for update to sadid_ledger_executor using(store_id=(select sadid_private.authorized_store())) with check(store_id=(select sadid_private.authorized_store()))',t);
 end loop;
end $$;

create function sadid_private.immutable_history() returns trigger language plpgsql set search_path='' as $$
begin raise exception using errcode='P0001',message='history_is_immutable'; end $$;
revoke all on function sadid_private.immutable_history() from public,anon,authenticated,service_role;
do $$ declare t text; begin
 foreach t in array array['sadid_debts','sadid_payments','sadid_adjustments','sadid_reversals','sadid_operations','sadid_events'] loop
  execute format('create trigger immutable before update or delete on public.%I for each row execute function sadid_private.immutable_history()',t);
 end loop;
 create trigger no_delete before delete on public.sadid_contacts for each row execute function sadid_private.immutable_history();
 create trigger no_delete before delete on public.sadid_ledger_state for each row execute function sadid_private.immutable_history();
 create trigger immutable before update or delete on sadid_private.control_events for each row execute function sadid_private.immutable_history();
end $$;

create function sadid_private.issue_capability(p_user uuid,p_session uuid,p_installation uuid,p_nonce uuid,p_scope text,p_request jsonb default null) returns jsonb
language plpgsql security definer set search_path='' as $$
declare b sadid_private.installation_sessions; v_token text; s public.stores;
begin
 if p_scope not in ('read','write','outcomes','lease') then raise exception 'invalid_scope'; end if;
 select x.* into b from sadid_private.installation_sessions x
 join sadid_private.installations i on i.id=x.installation_id and i.generation=x.generation and i.status='active'
 join sadid_private.store_memberships m on m.user_id=x.user_id and m.store_id=x.store_id
 join auth.sessions a on a.id=x.auth_session_id and a.user_id=x.user_id and (a.not_after is null or a.not_after>clock_timestamp())
 where x.auth_session_id=p_session and x.user_id=p_user and x.installation_id=p_installation;
 if not found then raise exception 'installation_or_session_revoked'; end if;
 select * into s from public.stores where id=b.store_id;
 if s.status<>'active' then raise exception 'store_suspended'; end if;
 if s.force_password_change then raise exception 'password_change_required'; end if;
 if p_scope='write' and (p_request is null or octet_length(p_request::text)>65536) then raise exception 'invalid_input'; end if;
 insert into sadid_private.request_nonces(installation_id,nonce) values(p_installation,p_nonce);
 v_token:=replace(gen_random_uuid()::text,'-','')||replace(gen_random_uuid()::text,'-','');
 insert into sadid_private.capabilities(token_hash,user_id,auth_session_id,store_id,installation_id,generation,scope,request,expires_at)
 values(pg_catalog.sha256(pg_catalog.convert_to(v_token,'UTF8')),p_user,p_session,b.store_id,p_installation,b.generation,p_scope,p_request,clock_timestamp()+interval '60 seconds');
 return jsonb_build_object('token',v_token,'expiresIn',60);
end $$;
revoke all on function sadid_private.issue_capability(uuid,uuid,uuid,uuid,text,jsonb) from public,anon,authenticated;
grant execute on function sadid_private.issue_capability(uuid,uuid,uuid,uuid,text,jsonb) to service_role;
create function public.sadid_issue_capability(p_user uuid,p_session uuid,p_installation uuid,p_nonce uuid,p_scope text,p_request jsonb default null) returns jsonb
language sql security invoker set search_path='' as $$ select sadid_private.issue_capability(p_user,p_session,p_installation,p_nonce,p_scope,p_request) $$;
revoke all on function public.sadid_issue_capability(uuid,uuid,uuid,uuid,text,jsonb) from public,anon,authenticated;
grant execute on function public.sadid_issue_capability(uuid,uuid,uuid,uuid,text,jsonb) to service_role;

create function sadid_private.apply(p_command jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities; st public.sadid_ledger_state; oldop public.sadid_operations;
 op uuid; entity uuid; kind text; payload jsonb; result jsonb; code text; dependency text;
 contact uuid; debt uuid; payment uuid; amount bigint; paid bigint; principal bigint; ver bigint;
v_phone text; occurred timestamptz; reason text; seq bigint;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'write' or c.request is distinct from p_command then raise exception 'authorization_required'; end if;
 if jsonb_typeof(p_command)<>'object' or p_command->>'schemaVersion'<>'1' or octet_length(p_command::text)>65536 then raise exception 'unsupported_schema'; end if;
 op:=(p_command->>'operationId')::uuid; entity:=(p_command->>'entityId')::uuid; kind:=p_command->>'type'; payload:=p_command->'payload';
 if op is null or entity is null or kind is null or jsonb_typeof(payload)<>'object' then raise exception 'invalid_input'; end if;
 if p_command->>'originInstallationId' is distinct from c.installation_id::text or (p_command->>'originGeneration')::integer is distinct from c.generation then raise exception 'installation_revoked'; end if;
 select * into st from public.sadid_ledger_state where store_id=c.store_id for update;
 if not found then raise exception 'ledger_not_initialized'; end if;
 select * into oldop from public.sadid_operations where store_id=c.store_id and operation_id=op;
 if found then
  if oldop.request is distinct from p_command then raise exception 'operation_id_reused'; end if;
  return oldop.result;
 end if;
 if jsonb_typeof(coalesce(p_command->'dependsOn','[]'))<>'array' or jsonb_array_length(coalesce(p_command->'dependsOn','[]'))>100 then raise exception 'invalid_dependencies'; end if;
 for dependency in select jsonb_array_elements_text(coalesce(p_command->'dependsOn','[]')) loop
  select * into oldop from public.sadid_operations where store_id=c.store_id and operation_id=dependency::uuid;
  if not found then raise exception 'dependency_pending'; end if;
  if oldop.result->>'status'<>'accepted' then code:='dependency_rejected'; exit; end if;
 end loop;
 occurred:=(p_command->>'createdAt')::timestamptz;
 if occurred is null then raise exception 'invalid_timestamp'; end if;
 -- يتراجع هذا الجزء وحده عند الرفض، ثم تُحفظ نتيجة الرفض الأصلية.
 if code is null then
 begin
  if kind='contact.create' then
   if length(trim(coalesce(payload->>'name',''))) not between 1 and 120 or length(coalesce(payload->>'note',''))>2000 or length(coalesce(payload->>'category',''))>80 or length(coalesce(payload->>'externalReference',''))>120 then raise exception 'invalid_input'; end if;
   v_phone:=regexp_replace(coalesce(payload->>'phone',''),'[[:space:]()\-]','','g');
   v_phone:=translate(v_phone,'٠١٢٣٤٥٦٧٨٩','0123456789');
   if v_phone<>'' and v_phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'invalid_phone'; end if;
   if v_phone<>'' and exists(select 1 from public.sadid_contacts where store_id=c.store_id and sadid_contacts.phone=v_phone) then raise exception 'duplicate_phone'; end if;
   insert into public.sadid_contacts(store_id,id,name,phone,category,note,external_reference,operation_id,created_at)
   values(c.store_id,entity,trim(payload->>'name'),v_phone,coalesce(payload->>'category',''),coalesce(payload->>'note',''),coalesce(payload->>'externalReference',''),op,occurred);
  elsif kind='debt.create' then
   contact:=(payload->>'contactId')::uuid;
   if not exists(select 1 from public.sadid_contacts where store_id=c.store_id and id=contact and not archived) then raise exception 'contact_unavailable'; end if;
   if jsonb_typeof(payload->'amountCents')<>'number' or payload->>'amountCents' !~ '^[0-9]+$' then raise exception 'invalid_money'; end if;
   amount:=(payload->>'amountCents')::bigint;
   if amount not between 1 and 9007199254740991 or payload->>'direction' not in ('receivable','payable') or length(coalesce(payload->>'note',''))>2000 then raise exception 'invalid_input'; end if;
   insert into public.sadid_debts(store_id,id,contact_id,direction,amount_cents,note,due_date,operation_id,created_at)
   values(c.store_id,entity,contact,payload->>'direction',amount,coalesce(payload->>'note',''),nullif(payload->>'dueDate','')::date,op,occurred);
  elsif kind in ('payment.create','debt.correct') then
   debt:=(payload->>'debtId')::uuid;
   select d.amount_cents into principal from public.sadid_debts d join public.sadid_contacts t on t.store_id=d.store_id and t.id=d.contact_id
   where d.store_id=c.store_id and d.id=debt and (kind='debt.correct' or not t.archived);
   if not found then raise exception 'debt_unavailable'; end if;
   select coalesce(max(a.version),1) into ver from public.sadid_adjustments a where a.store_id=c.store_id and a.debt_id=debt;
   select a.amount_cents into amount from public.sadid_adjustments a where a.store_id=c.store_id and a.debt_id=debt order by a.version desc limit 1;
   principal:=coalesce(amount,principal);
   select coalesce(sum(p.amount_cents),0) into paid from public.sadid_payments p where p.store_id=c.store_id and p.debt_id=debt
   and not exists(select 1 from public.sadid_reversals r where r.store_id=p.store_id and r.payment_id=p.id);
   if jsonb_typeof(payload->'amountCents')<>'number' or payload->>'amountCents' !~ '^[0-9]+$' then raise exception 'invalid_money'; end if;
   amount:=(payload->>'amountCents')::bigint;
   if amount not between 1 and 9007199254740991 then raise exception 'invalid_money'; end if;
   if kind='payment.create' then
    if amount>principal-paid then raise exception 'overpayment'; end if;
    if payload->>'method' not in ('cash','bank','wallet') or length(coalesce(payload->>'note',''))>2000 then raise exception 'invalid_input'; end if;
    insert into public.sadid_payments(store_id,id,debt_id,amount_cents,method,note,operation_id,created_at)
    values(c.store_id,entity,debt,amount,payload->>'method',coalesce(payload->>'note',''),op,occurred);
   else
    reason:=trim(coalesce(payload->>'reason',''));
    if length(reason) not between 1 and 2000 then raise exception 'reason_required'; end if;
    if coalesce((p_command->>'expectedVersion')::bigint,-1)<>ver then raise exception 'entity_version_changed'; end if;
    if amount<paid then raise exception 'below_paid_total'; end if;
    insert into public.sadid_adjustments(store_id,id,debt_id,amount_cents,previous_amount_cents,version,reason,operation_id)
    values(c.store_id,entity,debt,amount,principal,ver+1,reason,op);
   end if;
  elsif kind='payment.reverse' then
   payment:=(payload->>'paymentId')::uuid;reason:=trim(coalesce(payload->>'reason',''));
   if length(reason) not between 1 and 2000 then raise exception 'reason_required'; end if;
   if not exists(select 1 from public.sadid_payments where store_id=c.store_id and id=payment) then raise exception 'payment_unavailable'; end if;
   if exists(select 1 from public.sadid_reversals where store_id=c.store_id and payment_id=payment) then raise exception 'already_reversed'; end if;
   insert into public.sadid_reversals(store_id,id,payment_id,reason,operation_id) values(c.store_id,entity,payment,reason,op);
  elsif kind in ('contact.archive','contact.restore') then
   contact:=(payload->>'contactId')::uuid;
   if length(trim(coalesce(payload->>'reason',''))) not between 1 and 2000 then raise exception 'reason_required'; end if;
   update public.sadid_contacts set archived=(kind='contact.archive'),version=version+1 where store_id=c.store_id and id=contact and version=coalesce((p_command->>'expectedVersion')::bigint,-1);
   if not found then raise exception 'entity_version_changed'; end if;
  else raise exception 'unsupported_operation'; end if;
 exception
  when raise_exception then code:=sqlerrm;
  when unique_violation then code:='entity_id_exists';
  when check_violation or not_null_violation or invalid_text_representation or datetime_field_overflow or numeric_value_out_of_range then code:='invalid_input';
 end;
 end if;
 seq:=st.cursor+1;
 result:=jsonb_build_object('operationId',op,'entityId',entity,'status',case when code is null then 'accepted' else 'rejected' end,'code',code,'sequence',seq,'epoch',st.epoch,'serverTime',clock_timestamp());
 insert into public.sadid_operations(store_id,operation_id,request,result,user_id,installation_id,generation) values(c.store_id,op,p_command,result,c.user_id,c.installation_id,c.generation);
 insert into public.sadid_events(store_id,sequence,operation_id,kind,entity_id,user_id,installation_id,generation,command)
 values(c.store_id,seq,op,case when code is null then kind else 'operation.rejected' end,entity,c.user_id,c.installation_id,c.generation,p_command);
 update public.sadid_ledger_state set cursor=seq where store_id=c.store_id;
 return result;
end $$;
alter function sadid_private.apply(jsonb) owner to sadid_ledger_executor;
revoke all on function sadid_private.apply(jsonb) from public,anon,service_role;
grant execute on function sadid_private.apply(jsonb) to authenticated;
create function public.sadid_apply(p_command jsonb) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.apply(p_command) $$;
revoke all on function public.sadid_apply(jsonb) from public,anon,service_role;
grant execute on function public.sadid_apply(jsonb) to authenticated;

create function sadid_private.snapshot() returns jsonb language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities; s public.sadid_ledger_state; result jsonb;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'read' then raise exception 'authorization_required'; end if;
 select * into s from public.sadid_ledger_state where store_id=c.store_id for share;
 if not found then raise exception 'ledger_not_initialized'; end if;
 select jsonb_build_object('schemaVersion',1,'storeId',c.store_id,'epoch',s.epoch,'cursor',s.cursor,'currency',s.currency,
 'contacts',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_contacts t where store_id=c.store_id),'[]'),
 'debts',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_debts t where store_id=c.store_id),'[]'),
 'payments',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_payments t where store_id=c.store_id),'[]'),
 'adjustments',coalesce((select jsonb_agg(to_jsonb(t) order by version) from public.sadid_adjustments t where store_id=c.store_id),'[]'),
 'reversals',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_reversals t where store_id=c.store_id),'[]'),
 'outcomes',coalesce((select jsonb_agg(o.result order by o.received_at,o.operation_id) from public.sadid_operations o where o.store_id=c.store_id),'[]')) into result;
 return result;
end $$;
alter function sadid_private.snapshot() owner to sadid_ledger_executor;
revoke all on function sadid_private.snapshot() from public,anon,service_role;
grant execute on function sadid_private.snapshot() to authenticated;
create function public.sadid_snapshot() returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.snapshot() $$;
revoke all on function public.sadid_snapshot() from public,anon,service_role;
grant execute on function public.sadid_snapshot() to authenticated;

create function public.sadid_outcomes(p_ids uuid[]) returns jsonb language plpgsql security invoker set search_path='' as $$
declare c sadid_private.capabilities;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope not in ('outcomes','read') or coalesce(array_length(p_ids,1),0)>100 then raise exception 'authorization_required'; end if;
 return coalesce((select jsonb_agg(result) from public.sadid_operations where store_id=c.store_id and operation_id=any(p_ids)),'[]');
end $$;
revoke all on function public.sadid_outcomes(uuid[]) from public,anon,service_role;
grant execute on function public.sadid_outcomes(uuid[]) to authenticated;
revoke create on schema sadid_private from sadid_ledger_executor,sadid_auth_reader;
