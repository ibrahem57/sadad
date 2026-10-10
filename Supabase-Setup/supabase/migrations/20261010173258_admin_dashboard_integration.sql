-- Dashboard reads use the existing scoped, audited support role. No ledger
-- privileges are granted to the browser or to service_role directly.
create function sadid_private.admin_summaries(p_admin_hash text, p_stores bigint[]) returns jsonb
language plpgsql security definer set search_path='' as $$
declare actor_name text; store_number bigint; summary jsonb; result jsonb:='[]';
begin
 actor_name:=sadid_private.admin_session_username(p_admin_hash);
 if actor_name is null then raise exception 'admin_authorization_required'; end if;
 if cardinality(p_stores)>10000 then raise exception 'invalid_input'; end if;
 perform set_config('sadid.support.admin_hash',p_admin_hash,true);
 for store_number in select distinct unnest(p_stores) loop
  perform set_config('sadid.support.store',store_number::text,true);
  if not exists(select 1 from public.sadid_ledger_state where store_id=store_number) then
   result:=result||jsonb_build_array(jsonb_build_object('storeId',store_number,'initialized',false));
   continue;
  end if;
  with balances as (
   select d.id,d.direction,coalesce(a.amount_cents,d.amount_cents) as principal,
    coalesce(a.amount_cents,d.amount_cents)-coalesce(p.paid,0) as remaining
   from public.sadid_debts d
   left join lateral (select amount_cents from public.sadid_adjustments x where x.store_id=d.store_id and x.debt_id=d.id order by version desc limit 1) a on true
   left join lateral (select sum(x.amount_cents) as paid from public.sadid_payments x where x.store_id=d.store_id and x.debt_id=d.id and not exists(select 1 from public.sadid_reversals r where r.store_id=x.store_id and r.payment_id=x.id)) p on true
   where d.store_id=store_number
  )
  select jsonb_build_object('storeId',store_number,'initialized',true,
   'contacts',(select count(*) from public.sadid_contacts where store_id=store_number),
   'activeContacts',(select count(*) from public.sadid_contacts where store_id=store_number and not archived),
   'debts',count(*),'openDebts',count(*) filter(where remaining>0),
   'debtTotal',coalesce(sum(principal),0)/100.0,
   'receivable',coalesce(sum(remaining) filter(where direction='receivable'),0)/100.0,
   'payable',coalesce(sum(remaining) filter(where direction='payable'),0)/100.0,
   'payments',(select count(*) from public.sadid_payments where store_id=store_number))
  into summary from balances;
  insert into sadid_private.admin_access_events(store_id,actor,reason,outcome,record_counts)
  values(store_number,actor_name,'Dashboard financial summary','allowed',jsonb_build_object('contacts',summary->'contacts','debts',summary->'debts','payments',summary->'payments'));
  result:=result||jsonb_build_array(summary);
 end loop;
 return result;
end $$;
grant create on schema sadid_private to sadid_support_reader;
alter function sadid_private.admin_summaries(text,bigint[]) owner to sadid_support_reader;
revoke create on schema sadid_private from sadid_support_reader;
revoke all on function sadid_private.admin_summaries(text,bigint[]) from public,anon,authenticated;
grant execute on function sadid_private.admin_summaries(text,bigint[]) to service_role;
create function public.sadid_admin_summaries(p_admin_hash text,p_stores bigint[]) returns jsonb
language sql security invoker set search_path='' as $$ select sadid_private.admin_summaries(p_admin_hash,p_stores) $$;
revoke all on function public.sadid_admin_summaries(text,bigint[]) from public,anon,authenticated;
grant execute on function public.sadid_admin_summaries(text,bigint[]) to service_role;

-- Control-plane data has a separate authenticated admin gate. Financial
-- records remain accessible only through the audited support functions above.
create function sadid_private.admin_controls(p_admin_hash text,p_store bigint) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 if not exists(select 1 from public.sessions s join public.admins a on a.id=s.principal_id where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from statement_timestamp())*1000)::bigint) then raise exception 'admin_authorization_required'; end if;
 if not exists(select 1 from public.stores where id=p_store) then raise exception 'store_not_found'; end if;
 return jsonb_build_object(
  'hasDeviceHistory',exists(select 1 from sadid_private.installations where store_id=p_store) or exists(select 1 from sadid_private.store_memberships where store_id=p_store),
  'installations',coalesce((select jsonb_agg(jsonb_build_object('installationId',id,'storeId',store_id,'generation',generation,'status',status,'createdAt',created_at,'approvedAt',approved_at,'approvedBy',approved_by,'revokedAt',revoked_at,'reason',reason) order by generation desc) from sadid_private.installations where store_id=p_store),'[]'),
  'controlEvents',coalesce((select jsonb_agg(to_jsonb(t)) from (select id,actor,kind,reason,created_at from sadid_private.control_events where store_id=p_store order by id desc limit 100) t),'[]'),
  'accessEvents',coalesce((select jsonb_agg(to_jsonb(t)) from (select id,actor,reason,record_counts,created_at from sadid_private.admin_access_events where store_id=p_store order by created_at desc limit 50) t),'[]'),
  'recoveryPermits',coalesce((select jsonb_agg(to_jsonb(t)) from (select id,old_installation_id,new_installation_id,approved_by,reason,expires_at,created_at from sadid_private.recovery_permits where store_id=p_store order by created_at desc limit 20) t),'[]'));
end $$;
revoke all on function sadid_private.admin_controls(text,bigint) from public,anon,authenticated;
grant execute on function sadid_private.admin_controls(text,bigint) to service_role;
create function public.sadid_admin_controls(p_admin_hash text,p_store bigint) returns jsonb
language sql security invoker set search_path='' as $$ select sadid_private.admin_controls(p_admin_hash,p_store) $$;
revoke all on function public.sadid_admin_controls(text,bigint) from public,anon,authenticated;
grant execute on function public.sadid_admin_controls(text,bigint) to service_role;

-- Archive preserves immutable history. Ending sessions removes both legacy
-- bindings and ledger session authorizations, including outstanding capabilities.
create function sadid_private.admin_account_action(p_admin_hash text,p_store bigint,p_action text,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare actor_name text; account public.stores;
begin
 select a.username into actor_name from public.sessions s join public.admins a on a.id=s.principal_id where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from statement_timestamp())*1000)::bigint;
 if actor_name is null then raise exception 'admin_authorization_required'; end if;
 if p_action not in ('archive','unarchive','end-sessions','revoke-all') or length(trim(coalesce(p_reason,''))) not between 1 and 2000 then raise exception 'invalid_input'; end if;
 select * into account from public.stores where id=p_store for update;
 if not found then raise exception 'store_not_found'; end if;
 if p_action='unarchive' then
  if account.status<>'deleted' then raise exception 'store_not_archived'; end if;
  update public.stores set status='suspended',suspend_until=null,revision=revision+1 where id=p_store;
 else
  if p_action='archive' then update public.stores set status='deleted',suspend_until=null,revision=revision+1 where id=p_store; end if;
  if p_action in ('archive','revoke-all') then
   update sadid_private.installations set status='revoked',revoked_at=clock_timestamp(),reason=p_reason where store_id=p_store and status<>'revoked';
   update public.store_devices set revoked_at=(extract(epoch from clock_timestamp())*1000)::bigint where store_id=p_store and revoked_at is null;
  end if;
  delete from public.sessions where kind='store' and principal_id=p_store;
  delete from public.sadad_auth_bindings where store_id=p_store;
  delete from sadid_private.installation_sessions where store_id=p_store;
  delete from sadid_private.capabilities where store_id=p_store;
 end if;
 insert into sadid_private.control_events(store_id,actor,kind,reason,details) values(p_store,actor_name,'account.'||p_action,p_reason,'{}');
 return jsonb_build_object('storeId',p_store,'action',p_action);
end $$;
revoke all on function sadid_private.admin_account_action(text,bigint,text,text) from public,anon,authenticated;
grant execute on function sadid_private.admin_account_action(text,bigint,text,text) to service_role;
create function public.sadid_admin_account_action(p_admin_hash text,p_store bigint,p_action text,p_reason text) returns jsonb
language sql security invoker set search_path='' as $$ select sadid_private.admin_account_action(p_admin_hash,p_store,p_action,p_reason) $$;
revoke all on function public.sadid_admin_account_action(text,bigint,text,text) from public,anon,authenticated;
grant execute on function public.sadid_admin_account_action(text,bigint,text,text) to service_role;
