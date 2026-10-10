alter table sadid_private.recovery_permits add column epoch uuid;
create function sadid_private.recovery_epoch_allowed(p_epoch uuid) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from sadid_private.recovery_permits p where p.id=c.recovery_permit_id and p.store_id=c.store_id and p.user_id=c.user_id and p.new_installation_id=c.installation_id and p.epoch=p_epoch and p.expires_at>statement_timestamp() and exists(select 1 from jsonb_array_elements(p.manifest) x where x=c.request))
 from sadid_private.current_capability() c
$$;
grant create on schema sadid_private to sadid_auth_reader;
alter function sadid_private.recovery_epoch_allowed(uuid) owner to sadid_auth_reader;
revoke create on schema sadid_private from sadid_auth_reader;
revoke all on function sadid_private.recovery_epoch_allowed(uuid) from public,anon,authenticated,service_role;
grant execute on function sadid_private.recovery_epoch_allowed(uuid) to sadid_ledger_executor;

create role sadid_support_reader nologin nosuperuser nobypassrls noinherit;
grant sadid_support_reader to postgres;
grant usage on schema public,sadid_private to sadid_support_reader;
create table sadid_private.admin_access_events (
 id uuid primary key default gen_random_uuid(),store_id bigint not null,actor text not null,reason text not null,
 outcome text not null,record_counts jsonb not null,created_at timestamptz not null default clock_timestamp()
);
alter table sadid_private.admin_access_events enable row level security;
revoke all on sadid_private.admin_access_events from public,anon,authenticated,service_role;
grant insert on sadid_private.admin_access_events to sadid_support_reader;
create trigger immutable before update or delete on sadid_private.admin_access_events for each row execute function sadid_private.immutable_history();

create function sadid_private.admin_session_username(p_hash text) returns text language sql stable security definer set search_path='' as $$
 select a.username from public.sessions s join public.admins a on a.id=s.principal_id
 where s.token_hash=p_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from statement_timestamp())*1000)::bigint
$$;
revoke all on function sadid_private.admin_session_username(text) from public,anon,authenticated,service_role;
grant execute on function sadid_private.admin_session_username(text) to sadid_support_reader;
create function sadid_private.support_store() returns bigint language sql stable security invoker set search_path='' as $$
 select case when sadid_private.admin_session_username(current_setting('sadid.support.admin_hash',true)) is not null then nullif(current_setting('sadid.support.store',true),'')::bigint else null end
$$;
revoke all on function sadid_private.support_store() from public,anon,authenticated,service_role;
grant execute on function sadid_private.support_store() to sadid_support_reader;
create policy audited_support_insert on sadid_private.admin_access_events for insert to sadid_support_reader with check(actor=sadid_private.admin_session_username(current_setting('sadid.support.admin_hash',true)) and store_id=sadid_private.support_store());

do $$ declare t text; begin
 foreach t in array array['sadid_ledger_state','sadid_contacts','sadid_debts','sadid_payments','sadid_adjustments','sadid_reversals','sadid_operations','sadid_events'] loop
  execute format('grant select on public.%I to sadid_support_reader',t);
  execute format('create policy audited_support_read on public.%I for select to sadid_support_reader using(store_id=(select sadid_private.support_store()))',t);
 end loop;
end $$;

create function sadid_private.support_snapshot(p_admin_hash text,p_store bigint,p_reason text) returns jsonb language plpgsql security definer set search_path='' as $$
declare actor_name text; result jsonb; request_id uuid:=gen_random_uuid();
begin
 actor_name:=sadid_private.admin_session_username(p_admin_hash);
 if actor_name is null then raise exception 'admin_authorization_required'; end if;
 if length(trim(coalesce(p_reason,''))) not between 1 and 2000 then raise exception 'reason_required'; end if;
 perform set_config('sadid.support.admin_hash',p_admin_hash,true);perform set_config('sadid.support.store',p_store::text,true);
 select jsonb_build_object('storeId',p_store,'requestId',request_id,'epoch',s.epoch,'cursor',s.cursor,
 'contacts',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_contacts t where store_id=p_store),'[]'),
 'debts',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_debts t where store_id=p_store),'[]'),
 'payments',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_payments t where store_id=p_store),'[]'),
 'adjustments',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_adjustments t where store_id=p_store),'[]'),
 'reversals',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_reversals t where store_id=p_store),'[]'),
 'events',coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_events t where store_id=p_store),'[]')) into result from public.sadid_ledger_state s where store_id=p_store;
 if result is null then raise exception 'ledger_not_initialized'; end if;
 insert into sadid_private.admin_access_events(id,store_id,actor,reason,outcome,record_counts) values(request_id,p_store,actor_name,p_reason,'allowed',jsonb_build_object('contacts',jsonb_array_length(result->'contacts'),'debts',jsonb_array_length(result->'debts'),'payments',jsonb_array_length(result->'payments'),'events',jsonb_array_length(result->'events')));
 return result;
end $$;
grant create on schema sadid_private to sadid_support_reader;
alter function sadid_private.support_snapshot(text,bigint,text) owner to sadid_support_reader;
revoke create on schema sadid_private from sadid_support_reader;
revoke all on function sadid_private.support_snapshot(text,bigint,text) from public,anon,authenticated;
grant execute on function sadid_private.support_snapshot(text,bigint,text) to service_role;
create function public.sadid_support_snapshot(p_admin_hash text,p_store bigint,p_reason text) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.support_snapshot(p_admin_hash,p_store,p_reason) $$;
revoke all on function public.sadid_support_snapshot(text,bigint,text) from public,anon,authenticated;
grant execute on function public.sadid_support_snapshot(text,bigint,text) to service_role;

create or replace function sadid_private.approve_recovery(p_admin_hash text,p_old uuid,p_new uuid,p_manifest jsonb,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_actor text; old_i sadid_private.installations; new_i sadid_private.installations; p sadid_private.recovery_permits; cmd jsonb; v_user uuid;
begin
 select a.username into v_actor from public.sessions s join public.admins a on a.id=s.principal_id where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint;
 if not found then raise exception 'admin_authorization_required'; end if;
 if jsonb_typeof(p_manifest)<>'array' or jsonb_array_length(p_manifest) not between 1 and 1000 or octet_length(p_manifest::text)>4000000 or length(trim(coalesce(p_reason,''))) not between 1 and 2000 then raise exception 'invalid_manifest'; end if;
 select * into old_i from sadid_private.installations where id=p_old and status='revoked';
 select * into new_i from sadid_private.installations where id=p_new and status='active';
 if old_i.id is null or new_i.id is null or old_i.store_id<>new_i.store_id or old_i.generation>=new_i.generation then raise exception 'invalid_transfer'; end if;
 perform 1 from public.stores where id=new_i.store_id for update;
 if not exists(select 1 from sadid_private.installations where id=p_new and status='active') then raise exception 'installation_revoked'; end if;
 select user_id into v_user from sadid_private.store_memberships where store_id=new_i.store_id;
 for cmd in select jsonb_array_elements(p_manifest) loop
  if cmd->>'type' not in ('contact.create','debt.create','payment.create') or cmd->>'originInstallationId' is distinct from p_old::text or (cmd->>'originGeneration')::integer is distinct from old_i.generation or cmd->>'schemaVersion' is distinct from '1' or cmd->>'operationId' is null then raise exception 'invalid_manifest'; end if;
 end loop;
 if (select count(distinct x->>'operationId') from jsonb_array_elements(p_manifest) x)<>jsonb_array_length(p_manifest) then raise exception 'duplicate_operation_id'; end if;
 insert into sadid_private.recovery_permits(store_id,user_id,old_installation_id,new_installation_id,old_generation,manifest,manifest_hash,approved_by,reason,expires_at,epoch)
 values(new_i.store_id,v_user,p_old,p_new,old_i.generation,p_manifest,sha256(convert_to(p_manifest::text,'UTF8')),v_actor,p_reason,clock_timestamp()+interval '24 hours',(select epoch from public.sadid_ledger_state where store_id=new_i.store_id)) returning * into p;
 insert into sadid_private.control_events(store_id,actor,kind,reason,details) values(p.store_id,v_actor,'recovery.approved',p_reason,jsonb_build_object('permitId',p.id,'oldInstallationId',p_old,'newInstallationId',p_new,'manifestHash',encode(p.manifest_hash,'hex'),'count',jsonb_array_length(p_manifest)));
 return jsonb_build_object('permitId',p.id,'expiresAt',p.expires_at,'manifestHash',encode(p.manifest_hash,'hex'));
end $$;
create or replace function sadid_private.apply(p_command jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities; st public.sadid_ledger_state; oldop public.sadid_operations;
 op uuid; entity uuid; kind text; payload jsonb; result jsonb; code text; dependency text;
 contact uuid; debt uuid; payment uuid; amount bigint; paid bigint; principal bigint; ver bigint;
v_phone text; occurred timestamptz; reason text; seq bigint;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'write' or c.request is distinct from p_command then raise exception 'authorization_required'; end if;
 perform sadid_private.lock_authorization();
 c:=sadid_private.current_capability();
 if c.store_id is null then raise exception 'authorization_required'; end if;
 if not sadid_private.operation_allowed(p_command->>'type') then raise exception 'store_permission_denied'; end if;
 if jsonb_typeof(p_command)<>'object' or p_command->>'schemaVersion'<>'1' or octet_length(p_command::text)>65536 then raise exception 'unsupported_schema'; end if;
 op:=(p_command->>'operationId')::uuid; entity:=(p_command->>'entityId')::uuid; kind:=p_command->>'type'; payload:=p_command->'payload';
 if op is null or entity is null or kind is null or jsonb_typeof(payload)<>'object' then raise exception 'invalid_input'; end if;

 if not coalesce(sadid_private.origin_allowed(p_command),false) then raise exception 'installation_revoked'; end if;
 select * into st from public.sadid_ledger_state where store_id=c.store_id for update;
 if not found then raise exception 'ledger_not_initialized'; end if;
 select * into oldop from public.sadid_operations where store_id=c.store_id and operation_id=op;
 if found then
  if oldop.request is distinct from p_command then raise exception 'operation_id_reused'; end if;
  return oldop.result;
 end if;
 if p_command->>'expectedEpoch' is distinct from st.epoch::text and not coalesce(sadid_private.recovery_epoch_allowed(st.epoch),false) then raise exception 'epoch_changed'; end if;
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
   if (select count(*) from public.sadid_contacts where store_id=c.store_id and not archived)>=sadid_private.contact_limit() then raise exception 'contact_limit_reached'; end if;
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
