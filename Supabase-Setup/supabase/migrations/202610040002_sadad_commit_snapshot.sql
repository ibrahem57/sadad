-- Commit one validated offline ledger snapshot atomically.
-- Call only from the Edge Function with the service-role client.

create or replace function public.sadad_commit_store_snapshot(
  p_store_id bigint,
  p_base_revision bigint,
  p_snapshot jsonb,
  p_previous_snapshot jsonb,
  p_removed_contacts jsonb,
  p_audit_events jsonb,
  p_actor text,
  p_device_id text,
  p_received_at bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_revision bigint;
  item jsonb;
  snapshot_contacts jsonb := coalesce(p_snapshot->'contacts', '[]'::jsonb);
  snapshot_debts jsonb := coalesce(p_snapshot->'debts', '[]'::jsonb);
  snapshot_payments jsonb := coalesce(p_snapshot->'payments', '[]'::jsonb);
  previous_contacts jsonb := coalesce(p_previous_snapshot->'contacts', '[]'::jsonb);
  previous_debts jsonb := coalesce(p_previous_snapshot->'debts', '[]'::jsonb);
  previous_payments jsonb := coalesce(p_previous_snapshot->'payments', '[]'::jsonb);
begin
  select revision into current_revision
  from public.stores
  where id = p_store_id and status <> 'deleted'
  for update;

  if current_revision is null then
    raise exception using errcode = 'P0001', message = 'store_not_found';
  end if;
  if current_revision <> p_base_revision then
    raise exception using errcode = 'P0001', message = 'revision_conflict';
  end if;

  if jsonb_array_length(snapshot_contacts) = 0
     and jsonb_array_length(snapshot_debts) = 0
     and jsonb_array_length(snapshot_payments) = 0 then
    -- Empty snapshots are valid; keep a backup only when there was data to save.
    null;
  end if;

  if jsonb_array_length(previous_contacts) + jsonb_array_length(previous_debts) + jsonb_array_length(previous_payments) > 0 then
    insert into public.store_backups(store_id, created_at, reason, snapshot_json, contact_count, debt_count, payment_count)
    values (
      p_store_id,
      p_received_at,
      case when jsonb_array_length(coalesce(p_removed_contacts, '[]'::jsonb)) > 0 then 'قبل حذف زبون من التطبيق' else 'قبل مزامنة التطبيق' end,
      p_previous_snapshot,
      jsonb_array_length(previous_contacts),
      jsonb_array_length(previous_debts),
      jsonb_array_length(previous_payments)
    );

    delete from public.store_backups b
    where b.store_id = p_store_id
      and b.id not in (
        select recent.id from public.store_backups recent
        where recent.store_id = p_store_id
        order by recent.created_at desc, recent.id desc
        limit 100
      );
  end if;

  for item in select value from jsonb_array_elements(coalesce(p_removed_contacts, '[]'::jsonb)) loop
    insert into public.contact_archives(
      store_id, contact_id, contact_name, archived_at, source_revision,
      snapshot_json, debt_count, payment_count
    ) values (
      p_store_id,
      (item->'contact'->>'id')::bigint,
      left(coalesce(item->'contact'->>'name', ''), 120),
      p_received_at,
      p_base_revision,
      jsonb_build_object(
        'contact', item->'contact',
        'debts', coalesce(item->'debts', '[]'::jsonb),
        'payments', coalesce(item->'payments', '[]'::jsonb),
        'archivedAt', p_received_at
      ),
      jsonb_array_length(coalesce(item->'debts', '[]'::jsonb)),
      jsonb_array_length(coalesce(item->'payments', '[]'::jsonb))
    );
  end loop;

  delete from public.payments p
  where p.store_id = p_store_id
    and not exists (
      select 1 from jsonb_array_elements(snapshot_payments) row_data
      where (row_data->>'id')::bigint = p.id
    );

  delete from public.debts d
  where d.store_id = p_store_id
    and not exists (
      select 1 from jsonb_array_elements(snapshot_debts) row_data
      where (row_data->>'id')::bigint = d.id
    );

  delete from public.contacts c
  where c.store_id = p_store_id
    and not exists (
      select 1 from jsonb_array_elements(snapshot_contacts) row_data
      where (row_data->>'id')::bigint = c.id
    );

  for item in select value from jsonb_array_elements(snapshot_contacts) loop
    insert into public.contacts(
      store_id, id, name, phone, category, note, whatsapp_opt_in,
      credit_limit_cents, created_by, created_at
    ) values (
      p_store_id,
      (item->>'id')::bigint,
      left(coalesce(item->>'name', ''), 120),
      left(coalesce(item->>'phone', ''), 40),
      left(coalesce(item->>'category', ''), 40),
      left(coalesce(item->>'note', ''), 500),
      coalesce((item->>'whatsappOptIn')::boolean, false),
      round(coalesce((item->>'creditLimit')::numeric, 0) * 100)::bigint,
      coalesce(nullif(left(item->>'createdBy', 80), ''), left(coalesce(p_actor, ''), 80)),
      coalesce((item->>'createdAt')::bigint, p_received_at)
    )
    on conflict (store_id, id) do update set
      name = excluded.name,
      phone = excluded.phone,
      category = excluded.category,
      note = excluded.note,
      whatsapp_opt_in = excluded.whatsapp_opt_in,
      credit_limit_cents = excluded.credit_limit_cents;
  end loop;

  for item in select value from jsonb_array_elements(snapshot_debts) loop
    insert into public.debts(
      store_id, id, contact_id, direction, amount_cents, note, due_date, created_by, created_at
    ) values (
      p_store_id,
      (item->>'id')::bigint,
      (item->>'contactId')::bigint,
      item->>'direction',
      round((item->>'amount')::numeric * 100)::bigint,
      left(coalesce(item->>'note', ''), 500),
      left(coalesce(item->>'dueDate', ''), 20),
      left(coalesce(p_actor, ''), 80),
      coalesce((item->>'createdAt')::bigint, p_received_at)
    ) on conflict (store_id, id) do nothing;
  end loop;

  for item in select value from jsonb_array_elements(snapshot_payments) loop
    insert into public.payments(
      store_id, id, debt_id, amount_cents, method, note, created_by, created_at, server_received_at
    ) values (
      p_store_id,
      (item->>'id')::bigint,
      (item->>'debtId')::bigint,
      round((item->>'amount')::numeric * 100)::bigint,
      coalesce(nullif(item->>'method', ''), 'cash'),
      left(coalesce(item->>'note', ''), 500),
      left(coalesce(p_actor, ''), 80),
      coalesce((item->>'createdAt')::bigint, p_received_at),
      p_received_at
    ) on conflict (store_id, id) do nothing;
  end loop;

  for item in select value from jsonb_array_elements(coalesce(p_audit_events, '[]'::jsonb)) limit 300 loop
    insert into public.audit_events(store_id, device_id, actor, action, description, created_at)
    values (
      p_store_id,
      left(coalesce(p_device_id, ''), 120),
      left(coalesce(p_actor, ''), 80),
      left(coalesce(item->>'action', ''), 120),
      left(coalesce(item->>'description', ''), 500),
      p_received_at
    );
  end loop;

  delete from public.audit_events e
  where e.store_id = p_store_id
    and e.id not in (
      select recent.id from public.audit_events recent
      where recent.store_id = p_store_id
      order by recent.created_at desc, recent.id desc
      limit 5000
    );

  update public.stores
  set revision = revision + 1
  where id = p_store_id
  returning revision into current_revision;

  return current_revision;
end;
$$;

revoke all on function public.sadad_commit_store_snapshot(bigint, bigint, jsonb, jsonb, jsonb, jsonb, text, text, bigint)
  from public, anon, authenticated;
grant execute on function public.sadad_commit_store_snapshot(bigint, bigint, jsonb, jsonb, jsonb, jsonb, text, text, bigint)
  to service_role;
