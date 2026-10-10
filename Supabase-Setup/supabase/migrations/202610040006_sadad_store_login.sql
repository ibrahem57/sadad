-- Atomically enforce the per-store device cap while binding a device and creating its session.

create or replace function public.sadad_create_store_session(
  p_store_id bigint,
  p_token_hash text,
  p_expires_at bigint,
  p_now bigint,
  p_device_id text,
  p_device_label text,
  p_staff_name text
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_store public.stores%rowtype;
  current_device public.store_devices%rowtype;
  active_count integer;
  is_existing boolean;
begin
  select * into current_store from public.stores where id=p_store_id and status <> 'deleted' for update;
  if not found then raise exception using errcode='P0001',message='store_not_found'; end if;
  if current_store.status <> 'active'
     or current_store.subscription_mode = 'paused'
     or (current_store.subscription_mode = 'timed' and coalesce(current_store.subscription_expires_at,0) <= p_now) then
    raise exception using errcode='P0001',message='store_inactive';
  end if;

  select * into current_device from public.store_devices where store_id=p_store_id and device_id=p_device_id for update;
  is_existing := found;
  if is_existing and current_device.revoked_at is not null then raise exception using errcode='P0001',message='device_revoked'; end if;
  select count(*) into active_count from public.store_devices where store_id=p_store_id and revoked_at is null;
  if not is_existing and active_count >= current_store.allowed_devices then raise exception using errcode='P0001',message='device_limit'; end if;
  if current_store.allowed_devices > 1 and (not is_existing or current_device.staff_name = '') and length(trim(coalesce(p_staff_name,''))) < 2 then
    raise exception using errcode='P0001',message='device_staff_required';
  end if;

  if is_existing then
    update public.store_devices
    set last_seen_at=p_now, staff_name=case when staff_name='' then left(coalesce(p_staff_name,''),80) else staff_name end
    where store_id=p_store_id and device_id=p_device_id
    returning * into current_device;
  else
    insert into public.store_devices(store_id,device_id,label,first_seen_at,last_seen_at,staff_name,permissions)
    values (p_store_id,p_device_id,left(coalesce(p_device_label,''),120),p_now,p_now,
      case when current_store.allowed_devices > 1 then left(coalesce(p_staff_name,''),80) else '' end,
      '{"registerPayments":true,"deleteRecords":true,"deleteContacts":true}'::jsonb)
    returning * into current_device;
  end if;

  insert into public.sessions(token_hash,kind,principal_id,device_id,expires_at,created_at)
  values (p_token_hash,'store',p_store_id,p_device_id,p_expires_at,p_now);

  return jsonb_build_object('staff_name',current_device.staff_name,'permissions',current_device.permissions);
end;
$$;

revoke all on function public.sadad_create_store_session(bigint,text,bigint,bigint,text,text,text) from public, anon, authenticated;
grant execute on function public.sadad_create_store_session(bigint,text,bigint,bigint,text,text,text) to service_role;
