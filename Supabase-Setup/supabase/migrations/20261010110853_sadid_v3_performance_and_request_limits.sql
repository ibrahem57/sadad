-- فهارس المفاتيح الأجنبية وتحسين سياسة سجل الدعم وحدود الطلبات.
create index sadid_capabilities_installation_fk on sadid_private.capabilities(store_id,installation_id,generation);
create index sadid_session_installation_fk on sadid_private.installation_sessions(store_id,installation_id,generation);
create index sadid_recovery_old_installation_fk on sadid_private.recovery_permits(old_installation_id);
create index sadid_recovery_new_installation_fk on sadid_private.recovery_permits(new_installation_id);
create index sadid_nonce_rate on sadid_private.request_nonces(installation_id,created_at);
alter policy audited_support_insert on sadid_private.admin_access_events
 with check(actor=(select sadid_private.admin_session_username(current_setting('sadid.support.admin_hash',true))) and store_id=(select sadid_private.support_store()));
create function sadid_private.request_rate_guard() returns trigger language plpgsql security definer set search_path='' as $$
begin
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(new.installation_id::text,310));
 if (select count(*) from sadid_private.request_nonces where installation_id=new.installation_id and created_at>clock_timestamp()-interval '1 minute')>=300 then raise exception 'request_rate_limited'; end if;
 return new;
end $$;
revoke all on function sadid_private.request_rate_guard() from public,anon,authenticated,service_role;
create trigger sadid_request_rate before insert on sadid_private.request_nonces for each row execute function sadid_private.request_rate_guard();
create function sadid_private.enrollment_rate_guard() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.status='pending' and (select count(*) from sadid_private.installations where store_id=new.store_id and status='pending')>=20 then raise exception 'enrollment_rate_limited'; end if;
 return new;
end $$;
revoke all on function sadid_private.enrollment_rate_guard() from public,anon,authenticated,service_role;
create trigger sadid_enrollment_rate before insert on sadid_private.installations for each row execute function sadid_private.enrollment_rate_guard();
