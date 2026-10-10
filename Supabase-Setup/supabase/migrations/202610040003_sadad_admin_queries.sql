-- Keep dashboard aggregations inside Postgres so Edge responses do not have to
-- download every ledger row just to calculate counts and totals.

create or replace function public.sadad_admin_overview()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'stores', (select count(*) from public.stores where status <> 'deleted'),
    'active', (select count(*) from public.stores where status = 'active'),
    'trusted', (select count(*) from public.stores where verified and status = 'active'),
    'contacts', (select count(*) from public.contacts),
    'debts', round(coalesce((select sum(amount_cents) from public.debts), 0) / 100.0, 2)
  );
$$;

create or replace function public.sadad_admin_store_list()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select coalesce(jsonb_agg(
    jsonb_build_object(
      'id', s.id,
      'name', s.name,
      'username', s.username,
      'status', s.status,
      'suspend_until', s.suspend_until,
      'subscription_mode', s.subscription_mode,
      'subscription_started_at', s.subscription_started_at,
      'subscription_expires_at', s.subscription_expires_at,
      'subscription_remaining_ms', case
        when s.subscription_mode = 'permanent' then null
        when s.subscription_mode = 'paused' then greatest(0, coalesce(s.subscription_paused_remaining_ms, 0))
        else greatest(0, coalesce(s.subscription_expires_at, 0) - (extract(epoch from clock_timestamp()) * 1000)::bigint)
      end,
      'allowed_devices', s.allowed_devices,
      'verified', s.verified,
      'debtor_limit', s.debtor_limit,
      'permissions', s.permissions,
      'whatsapp_enabled', s.whatsapp_enabled,
      'revision', s.revision,
      'created_at', s.created_at,
      'contacts', coalesce(c.contact_count, 0),
      'debts', coalesce(d.debt_count, 0),
      'debt_total', round(coalesce(d.debt_cents, 0) / 100.0, 2),
      'device_count', coalesce(v.device_count, 0)
    ) order by s.created_at desc, s.id desc
  ), '[]'::jsonb)
  from public.stores s
  left join lateral (
    select count(*) as contact_count from public.contacts where store_id = s.id
  ) c on true
  left join lateral (
    select count(*) as debt_count, sum(amount_cents) as debt_cents from public.debts where store_id = s.id
  ) d on true
  left join lateral (
    select count(*) as device_count from public.store_devices where store_id = s.id and revoked_at is null
  ) v on true
  where s.status <> 'deleted';
$$;

revoke all on function public.sadad_admin_overview() from public, anon, authenticated;
revoke all on function public.sadad_admin_store_list() from public, anon, authenticated;
grant execute on function public.sadad_admin_overview() to service_role;
grant execute on function public.sadad_admin_store_list() to service_role;
