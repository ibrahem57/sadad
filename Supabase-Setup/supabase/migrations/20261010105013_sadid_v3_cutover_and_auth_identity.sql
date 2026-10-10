create function sadid_private.store_auth_identity(p_store bigint) returns text language sql stable security definer set search_path='' as $$
 select u.email from sadid_private.store_memberships m join auth.users u on u.id=m.user_id where m.store_id=p_store
$$;
revoke all on function sadid_private.store_auth_identity(bigint) from public,anon,authenticated;
grant execute on function sadid_private.store_auth_identity(bigint) to service_role;
create function public.sadid_store_auth_identity(p_store bigint) returns text language sql stable security invoker set search_path='' as $$ select sadid_private.store_auth_identity(p_store) $$;
revoke all on function public.sadid_store_auth_identity(bigint) from public,anon,authenticated;
grant execute on function public.sadid_store_auth_identity(bigint) to service_role;
create function public.sadid_is_v3_store(p_store bigint) returns boolean language sql stable security definer set search_path='' as $$ select exists(select 1 from public.sadid_ledger_state where store_id=p_store) $$;
revoke all on function public.sadid_is_v3_store(bigint) from public,anon,authenticated;
grant execute on function public.sadid_is_v3_store(bigint) to service_role;

create function sadid_private.block_legacy_write() returns trigger language plpgsql security definer set search_path='' as $$
declare old_store bigint;new_store bigint;
begin
 old_store:=case when tg_op<>'INSERT' then old.store_id else null end;
 new_store:=case when tg_op<>'DELETE' then new.store_id else null end;
 if exists(select 1 from public.sadid_ledger_state where store_id in (old_store,new_store)) then raise exception 'legacy_ledger_read_only'; end if;
 return case when tg_op='DELETE' then old else new end;
end $$;
revoke all on function sadid_private.block_legacy_write() from public,anon,authenticated,service_role;
create trigger sadid_v3_legacy_guard before insert or update or delete on public.contacts for each row execute function sadid_private.block_legacy_write();
create trigger sadid_v3_legacy_guard before insert or update or delete on public.debts for each row execute function sadid_private.block_legacy_write();
create trigger sadid_v3_legacy_guard before insert or update or delete on public.payments for each row execute function sadid_private.block_legacy_write();
