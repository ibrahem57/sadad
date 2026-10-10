create or replace function sadid_private.current_capability() returns sadid_private.capabilities
language sql stable security definer set search_path='' as $$
 select c from sadid_private.capabilities c
 join sadid_private.store_memberships m on m.user_id=c.user_id and m.store_id=c.store_id
 join sadid_private.installations i on i.id=c.installation_id and i.store_id=c.store_id and i.generation=c.generation and i.status='active'
 join sadid_private.installation_sessions b on b.auth_session_id=c.auth_session_id and b.user_id=c.user_id and b.installation_id=c.installation_id and b.generation=c.generation
 join public.stores s on s.id=c.store_id and s.status='active' and not s.force_password_change and s.subscription_mode<>'paused' and (s.subscription_mode<>'timed' or s.subscription_expires_at>(extract(epoch from statement_timestamp())*1000)::bigint)
 where c.token_hash=pg_catalog.sha256(pg_catalog.convert_to(coalesce(nullif(current_setting('request.headers',true),'')::jsonb->>'x-sadad-capability',''),'UTF8'))
 and c.user_id=(nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid
 and c.auth_session_id=(nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'session_id')::uuid
 and c.expires_at>statement_timestamp()
 and sadid_private.auth_session_active(c.user_id,c.auth_session_id)
 limit 1
$$;

create function sadid_private.lock_authorization() returns void language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null then raise exception 'authorization_required'; end if;
 perform 1 from public.stores where id=c.store_id for update;
 perform 1 from sadid_private.installations where id=c.installation_id for share;
 perform 1 from auth.sessions where id=c.auth_session_id and user_id=c.user_id for share;
end $$;
revoke all on function sadid_private.lock_authorization() from public,anon,authenticated,service_role;
grant execute on function sadid_private.lock_authorization() to sadid_ledger_executor;
create function sadid_private.operation_allowed(p_kind text) returns boolean language sql stable security definer set search_path='' as $$
 select coalesce((s.permissions->>case when p_kind like 'contact.%' then 'contacts' when p_kind like 'debt.%' then 'debts' when p_kind like 'payment.%' then 'payments' else 'invalid' end)::boolean,true)
 from public.stores s where id=(sadid_private.current_capability()).store_id
$$;
create function sadid_private.contact_limit() returns integer language sql stable security definer set search_path='' as $$
 select debtor_limit from public.stores where id=(sadid_private.current_capability()).store_id
$$;
grant create on schema sadid_private to sadid_auth_reader;
alter function sadid_private.operation_allowed(text) owner to sadid_auth_reader;
alter function sadid_private.contact_limit() owner to sadid_auth_reader;
revoke create on schema sadid_private from sadid_auth_reader;
revoke all on function sadid_private.operation_allowed(text),sadid_private.contact_limit() from public,anon,authenticated,service_role;
grant execute on function sadid_private.operation_allowed(text),sadid_private.contact_limit() to sadid_ledger_executor;

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
 if p_command ? 'expectedEpoch' and p_command->>'expectedEpoch' is distinct from (select epoch::text from public.sadid_ledger_state where store_id=c.store_id) then raise exception 'epoch_changed'; end if;
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
create or replace function sadid_private.snapshot() returns jsonb language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities; s public.sadid_ledger_state; result jsonb;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'read' then raise exception 'authorization_required'; end if;
 perform sadid_private.lock_authorization();
 c:=sadid_private.current_capability();
 if c.store_id is null then raise exception 'authorization_required'; end if;
 select * into s from public.sadid_ledger_state where store_id=c.store_id for share;
 if not found then raise exception 'ledger_not_initialized'; end if;
 select jsonb_build_object('schemaVersion',1,'storeId',c.store_id,'epoch',s.epoch,'cursor',s.cursor,'currency',s.currency,
 'debtBalances',coalesce((select jsonb_agg(jsonb_build_object('debtId',d.id,'amountCents',coalesce(a.amount_cents,d.amount_cents),'paidCents',coalesce(p.paid,0),'remainingCents',coalesce(a.amount_cents,d.amount_cents)-coalesce(p.paid,0),'version',coalesce(a.version,1))) from public.sadid_debts d
 left join lateral (select amount_cents,version from public.sadid_adjustments x where x.store_id=d.store_id and x.debt_id=d.id order by version desc limit 1) a on true
 left join lateral (select sum(x.amount_cents) paid from public.sadid_payments x where x.store_id=d.store_id and x.debt_id=d.id and not exists(select 1 from public.sadid_reversals r where r.store_id=x.store_id and r.payment_id=x.id)) p on true
 where d.store_id=c.store_id),'[]'),
 'contacts',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_contacts t where store_id=c.store_id),'[]'),
 'debts',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_debts t where store_id=c.store_id),'[]'),
 'payments',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_payments t where store_id=c.store_id),'[]'),
 'adjustments',coalesce((select jsonb_agg(to_jsonb(t) order by version) from public.sadid_adjustments t where store_id=c.store_id),'[]'),
 'reversals',coalesce((select jsonb_agg(to_jsonb(t) order by id) from public.sadid_reversals t where store_id=c.store_id),'[]'),
 'outcomes',coalesce((select jsonb_agg(o.result order by o.received_at,o.operation_id) from public.sadid_operations o where o.store_id=c.store_id),'[]')) into result;
 return result;
end $$;
