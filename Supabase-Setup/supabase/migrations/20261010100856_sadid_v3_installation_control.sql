create function sadid_private.enroll(p_user uuid,p_session uuid,p_legacy_hash text,p_installation uuid,p_key jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare b public.sadad_auth_bindings; i sadid_private.installations; v_generation integer;
begin
 if p_key->>'kty' is distinct from 'EC' or p_key->>'crv' is distinct from 'P-256'
 or p_key ? 'd' or length(coalesce(p_key->>'x',''))<>43 or length(coalesce(p_key->>'y',''))<>43
 then raise exception 'invalid_public_key'; end if;
 select x.* into b from public.sadad_auth_bindings x
 join auth.sessions a on a.id=x.auth_session_id and a.user_id=x.user_id and (a.not_after is null or a.not_after>clock_timestamp())
 join public.sessions l on l.token_hash=x.session_hash and l.kind='store' and l.principal_id=x.store_id and l.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint
 join public.stores s on s.id=x.store_id and s.status='active' and not s.force_password_change
 where x.session_hash=p_legacy_hash and x.user_id=p_user and x.auth_session_id=p_session
 and x.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint;
 if not found then raise exception 'authorization_required'; end if;
 perform 1 from public.stores where id=b.store_id for update;
 insert into sadid_private.store_memberships(user_id,store_id) values(p_user,b.store_id) on conflict(user_id) do nothing;
 if not exists(select 1 from sadid_private.store_memberships where user_id=p_user and store_id=b.store_id) then raise exception 'membership_conflict'; end if;
 select * into i from sadid_private.installations where id=p_installation;
 if found then
  if i.store_id<>b.store_id or i.public_key<>p_key then raise exception 'installation_key_changed'; end if;
  if i.status='revoked' then raise exception 'installation_revoked'; end if;
 else
  select coalesce(max(generation),0)+1 into v_generation from sadid_private.installations where store_id=b.store_id;
  insert into sadid_private.installations(id,store_id,generation,public_key,status) values(p_installation,b.store_id,v_generation,p_key,'pending') returning * into i;
 end if;
 insert into sadid_private.installation_sessions(auth_session_id,user_id,store_id,installation_id,generation)
 values(p_session,p_user,b.store_id,p_installation,i.generation)
 on conflict(auth_session_id) do nothing;
 if not exists(select 1 from sadid_private.installation_sessions where auth_session_id=p_session and user_id=p_user and installation_id=p_installation and generation=i.generation) then raise exception 'session_installation_conflict'; end if;
 return jsonb_build_object('installationId',i.id,'generation',i.generation,'status',i.status);
end $$;

create function sadid_private.approve_installation(p_admin_hash text,p_installation uuid,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_actor text; i sadid_private.installations; old_id uuid;
begin
 select a.username into v_actor from public.sessions s join public.admins a on a.id=s.principal_id
 where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint;
 if not found then raise exception 'admin_authorization_required'; end if;
 if length(trim(coalesce(p_reason,''))) not between 1 and 2000 then raise exception 'reason_required'; end if;
 select * into i from sadid_private.installations where id=p_installation;
 if not found or i.status='revoked' then raise exception 'installation_unavailable'; end if;
 perform 1 from public.stores where id=i.store_id for update;
 if i.status='active' then return jsonb_build_object('installationId',i.id,'generation',i.generation,'status','active'); end if;
 select id into old_id from sadid_private.installations where store_id=i.store_id and status='active';
 update sadid_private.installations set status='revoked',revoked_at=clock_timestamp(),reason=p_reason where store_id=i.store_id and status='active';
 update sadid_private.installations set status='active',approved_by=v_actor,approved_at=clock_timestamp() where id=i.id;
 insert into public.sadid_ledger_state(store_id) values(i.store_id) on conflict do nothing;
 insert into sadid_private.control_events(store_id,actor,kind,reason,details)
 values(i.store_id,v_actor,'installation.approved',p_reason,jsonb_build_object('oldInstallationId',old_id,'newInstallationId',i.id,'generation',i.generation));
 return jsonb_build_object('installationId',i.id,'generation',i.generation,'status','active','oldInstallationId',old_id);
end $$;

create function sadid_private.installation_key(p_user uuid,p_session uuid,p_installation uuid) returns jsonb
language sql stable security definer set search_path='' as $$
 select jsonb_build_object('publicKey',i.public_key,'storeId',i.store_id,'generation',i.generation,'status',i.status)
 from sadid_private.installations i join sadid_private.installation_sessions s on s.installation_id=i.id and s.generation=i.generation
 join auth.sessions a on a.id=s.auth_session_id and a.user_id=s.user_id and (a.not_after is null or a.not_after>statement_timestamp())
 where i.id=p_installation and s.user_id=p_user and s.auth_session_id=p_session and i.status='active'
$$;

create function sadid_private.pending_installations(p_admin_hash text) returns jsonb
language plpgsql stable security definer set search_path='' as $$
begin
 if not exists(select 1 from public.sessions s join public.admins a on a.id=s.principal_id where s.token_hash=p_admin_hash and s.kind='admin' and not a.force_password_change and s.expires_at>(extract(epoch from statement_timestamp())*1000)::bigint) then raise exception 'admin_authorization_required'; end if;
 return coalesce((select jsonb_agg(jsonb_build_object('installationId',i.id,'storeId',i.store_id,'generation',i.generation,'status',i.status,'createdAt',i.created_at)) from sadid_private.installations i),'[]');
end $$;

create function public.sadid_enroll(p_user uuid,p_session uuid,p_legacy_hash text,p_installation uuid,p_key jsonb) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.enroll(p_user,p_session,p_legacy_hash,p_installation,p_key) $$;
create function public.sadid_approve_installation(p_admin_hash text,p_installation uuid,p_reason text) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.approve_installation(p_admin_hash,p_installation,p_reason) $$;
create function public.sadid_installation_key(p_user uuid,p_session uuid,p_installation uuid) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.installation_key(p_user,p_session,p_installation) $$;
create function public.sadid_pending_installations(p_admin_hash text) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.pending_installations(p_admin_hash) $$;
revoke all on function sadid_private.enroll(uuid,uuid,text,uuid,jsonb),sadid_private.approve_installation(text,uuid,text),sadid_private.installation_key(uuid,uuid,uuid),sadid_private.pending_installations(text),public.sadid_enroll(uuid,uuid,text,uuid,jsonb),public.sadid_approve_installation(text,uuid,text),public.sadid_installation_key(uuid,uuid,uuid),public.sadid_pending_installations(text) from public,anon,authenticated;
grant execute on function sadid_private.enroll(uuid,uuid,text,uuid,jsonb),sadid_private.approve_installation(text,uuid,text),sadid_private.installation_key(uuid,uuid,uuid),sadid_private.pending_installations(text),public.sadid_enroll(uuid,uuid,text,uuid,jsonb),public.sadid_approve_installation(text,uuid,text),public.sadid_installation_key(uuid,uuid,uuid),public.sadid_pending_installations(text) to service_role;

create index sadid_debts_contact on public.sadid_debts(store_id,contact_id);
create index sadid_payments_debt on public.sadid_payments(store_id,debt_id);
create index sadid_adjustments_debt on public.sadid_adjustments(store_id,debt_id,version desc);
create index sadid_events_operation on public.sadid_events(store_id,operation_id);
create index sadid_capability_user on sadid_private.capabilities(user_id,auth_session_id);
create index sadid_installation_session_user on sadid_private.installation_sessions(user_id,store_id);
