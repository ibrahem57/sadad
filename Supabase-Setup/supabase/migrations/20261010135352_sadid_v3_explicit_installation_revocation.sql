-- إلغاء تركيب صريح، مستقل عن انتظار جهاز بديل، مع سجل قرار الإدارة.
create function sadid_private.revoke_installation(p_admin_hash text,p_installation uuid,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare actor_name text; i sadid_private.installations; store_number bigint;
begin
 select a.username into actor_name from public.sessions s join public.admins a on a.id=s.principal_id where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint;
 if not found then raise exception 'admin_authorization_required'; end if;
 if length(trim(coalesce(p_reason,''))) not between 1 and 2000 then raise exception 'invalid_input'; end if;
 select store_id into store_number from sadid_private.installations where id=p_installation;
 if not found then raise exception 'installation_unavailable'; end if;
 perform 1 from public.stores where id=store_number for update;
 select * into i from sadid_private.installations where id=p_installation for update;
 update sadid_private.installations set status='revoked' where id=i.id;
 update public.store_devices set revoked_at=(extract(epoch from clock_timestamp())*1000)::bigint where store_id=i.store_id and device_id=i.id::text and revoked_at is null;
 delete from public.sessions where kind='store' and principal_id=i.store_id and device_id=i.id::text;
 insert into sadid_private.control_events(store_id,actor,kind,reason,details) values(i.store_id,actor_name,'installation.revoked',p_reason,jsonb_build_object('installationId',i.id,'generation',i.generation));
 return jsonb_build_object('installationId',i.id,'status','revoked');
end $$;
create function public.sadid_revoke_installation(p_admin_hash text,p_installation uuid,p_reason text) returns jsonb language sql security invoker set search_path='' as $$select sadid_private.revoke_installation(p_admin_hash,p_installation,p_reason)$$;
revoke all on function sadid_private.revoke_installation(text,uuid,text),public.sadid_revoke_installation(text,uuid,text) from public,anon,authenticated,service_role;
grant execute on function sadid_private.revoke_installation(text,uuid,text),public.sadid_revoke_installation(text,uuid,text) to service_role;
