-- Preserve atomic per-account throttling and admin recovery operations.

create or replace function public.sadad_record_login_failure(
  p_bucket_key text,
  p_failure_limit integer,
  p_now bigint,
  p_window_ms bigint,
  p_block_ms bigint
)
returns void
language sql
security definer
set search_path = public, pg_temp
as $$
  insert into public.login_rate_limits(bucket_key, failures, window_started_at, blocked_until)
  values (p_bucket_key, 1, p_now, 0)
  on conflict (bucket_key) do update set
    failures = case
      when p_now - public.login_rate_limits.window_started_at < p_window_ms then public.login_rate_limits.failures + 1
      else 1
    end,
    window_started_at = case
      when p_now - public.login_rate_limits.window_started_at < p_window_ms then public.login_rate_limits.window_started_at
      else p_now
    end,
    blocked_until = case
      when (case
        when p_now - public.login_rate_limits.window_started_at < p_window_ms then public.login_rate_limits.failures + 1
        else 1
      end) >= p_failure_limit then p_now + p_block_ms
      else 0
    end;
$$;

create or replace function public.sadad_restore_store_backup(
  p_store_id bigint,
  p_backup_id bigint,
  p_contact_id bigint,
  p_current_snapshot jsonb,
  p_now bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_revision bigint;
  saved_snapshot jsonb;
  contacts_json jsonb;
  debts_json jsonb;
  payments_json jsonb;
  selected_contact jsonb;
  item jsonb;
  debt_map jsonb := '{}'::jsonb;
  old_id bigint;
  new_id bigint;
  next_id bigint;
begin
  select revision into current_revision from public.stores where id = p_store_id and status <> 'deleted' for update;
  if current_revision is null then raise exception using errcode = 'P0001', message = 'store_not_found'; end if;

  select snapshot_json into saved_snapshot from public.store_backups where id = p_backup_id and store_id = p_store_id;
  if saved_snapshot is null then raise exception using errcode = 'P0001', message = 'backup_not_found'; end if;
  contacts_json := coalesce(saved_snapshot->'contacts', '[]'::jsonb);
  debts_json := coalesce(saved_snapshot->'debts', '[]'::jsonb);
  payments_json := coalesce(saved_snapshot->'payments', '[]'::jsonb);

  insert into public.store_backups(store_id, created_at, reason, snapshot_json, contact_count, debt_count, payment_count)
  values (p_store_id, p_now, case when p_contact_id is null then 'قبل استعادة نسخة المتجر' else 'قبل استعادة سجل شخص' end,
    p_current_snapshot,
    jsonb_array_length(coalesce(p_current_snapshot->'contacts', '[]'::jsonb)),
    jsonb_array_length(coalesce(p_current_snapshot->'debts', '[]'::jsonb)),
    jsonb_array_length(coalesce(p_current_snapshot->'payments', '[]'::jsonb)));
  delete from public.store_backups b where b.store_id = p_store_id and b.id not in (
    select recent.id from public.store_backups recent where recent.store_id = p_store_id order by recent.created_at desc, recent.id desc limit 100
  );

  if p_contact_id is null then
    delete from public.contacts where store_id = p_store_id;
    for item in select value from jsonb_array_elements(contacts_json) loop
      insert into public.contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_by,created_at)
      values (p_store_id,(item->>'id')::bigint,left(coalesce(item->>'name',''),120),left(coalesce(item->>'phone',''),40),
        left(coalesce(item->>'category',''),40),left(coalesce(item->>'note',''),500),coalesce((item->>'whatsappOptIn')::boolean,false),
        round(coalesce((item->>'creditLimit')::numeric,0)*100)::bigint,left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now));
    end loop;
    for item in select value from jsonb_array_elements(debts_json) loop
      insert into public.debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_by,created_at)
      values (p_store_id,(item->>'id')::bigint,(item->>'contactId')::bigint,
        case when item->>'direction'='payable' then 'payable' else 'receivable' end,
        round((item->>'amount')::numeric*100)::bigint,left(coalesce(item->>'note',''),500),left(coalesce(item->>'dueDate',''),20),
        left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now));
    end loop;
    for item in select value from jsonb_array_elements(payments_json) loop
      insert into public.payments(store_id,id,debt_id,amount_cents,method,note,created_by,created_at,server_received_at)
      values (p_store_id,(item->>'id')::bigint,(item->>'debtId')::bigint,round((item->>'amount')::numeric*100)::bigint,
        case when item->>'method' in ('cash','bank','wallet') then item->>'method' else 'cash' end,
        left(coalesce(item->>'note',''),500),left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now),p_now);
    end loop;
  else
    select value into selected_contact from jsonb_array_elements(contacts_json) where (value->>'id')::bigint = p_contact_id limit 1;
    if selected_contact is null then raise exception using errcode = 'P0001', message = 'backup_contact_not_found'; end if;
    delete from public.contacts where store_id = p_store_id and id = p_contact_id;
    insert into public.contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_by,created_at)
    values (p_store_id,p_contact_id,left(coalesce(selected_contact->>'name',''),120),left(coalesce(selected_contact->>'phone',''),40),
      left(coalesce(selected_contact->>'category',''),40),left(coalesce(selected_contact->>'note',''),500),coalesce((selected_contact->>'whatsappOptIn')::boolean,false),
      round(coalesce((selected_contact->>'creditLimit')::numeric,0)*100)::bigint,left(coalesce(selected_contact->>'createdBy',''),80),coalesce((selected_contact->>'createdAt')::bigint,p_now));

    select coalesce(max(id),0)+1 into next_id from public.debts where store_id = p_store_id;
    for item in select value from jsonb_array_elements(debts_json) where (value->>'contactId')::bigint = p_contact_id loop
      old_id := (item->>'id')::bigint; new_id := old_id;
      if exists(select 1 from public.debts where store_id=p_store_id and id=new_id) then new_id := next_id; next_id := next_id+1; end if;
      debt_map := debt_map || jsonb_build_object(old_id::text,new_id);
      insert into public.debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_by,created_at)
      values (p_store_id,new_id,p_contact_id,case when item->>'direction'='payable' then 'payable' else 'receivable' end,
        round((item->>'amount')::numeric*100)::bigint,left(coalesce(item->>'note',''),500),left(coalesce(item->>'dueDate',''),20),
        left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now));
    end loop;

    select coalesce(max(id),0)+1 into next_id from public.payments where store_id = p_store_id;
    for item in select value from jsonb_array_elements(payments_json) loop
      old_id := (item->>'debtId')::bigint;
      if debt_map ? old_id::text then
        new_id := (item->>'id')::bigint;
        if exists(select 1 from public.payments where store_id=p_store_id and id=new_id) then new_id := next_id; next_id := next_id+1; end if;
        insert into public.payments(store_id,id,debt_id,amount_cents,method,note,created_by,created_at,server_received_at)
        values (p_store_id,new_id,(debt_map->>old_id::text)::bigint,round((item->>'amount')::numeric*100)::bigint,
          case when item->>'method' in ('cash','bank','wallet') then item->>'method' else 'cash' end,
          left(coalesce(item->>'note',''),500),left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now),p_now);
      end if;
    end loop;
  end if;

  update public.stores set revision=revision+1 where id=p_store_id returning revision into current_revision;
  return current_revision;
end;
$$;

create or replace function public.sadad_restore_contact_archive(
  p_store_id bigint,
  p_archive_id bigint,
  p_current_snapshot jsonb,
  p_now bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  current_revision bigint;
  archive_row public.contact_archives%rowtype;
  archive_json jsonb;
  contact_json jsonb;
  item jsonb;
  debt_map jsonb := '{}'::jsonb;
  contact_id bigint;
  old_id bigint;
  new_id bigint;
  next_id bigint;
begin
  select revision into current_revision from public.stores where id=p_store_id and status <> 'deleted' for update;
  if current_revision is null then raise exception using errcode='P0001',message='store_not_found'; end if;
  select * into archive_row from public.contact_archives where id=p_archive_id and store_id=p_store_id for update;
  if not found then raise exception using errcode='P0001',message='archive_not_found'; end if;
  if archive_row.restored_at is not null then raise exception using errcode='P0001',message='archive_already_restored'; end if;
  archive_json := archive_row.snapshot_json;
  contact_json := archive_json->'contact';
  contact_id := (contact_json->>'id')::bigint;
  if exists(select 1 from public.contacts where store_id=p_store_id and id=contact_id) then
    raise exception using errcode='P0001',message='archive_contact_exists';
  end if;

  if jsonb_array_length(coalesce(p_current_snapshot->'contacts','[]'::jsonb)) + jsonb_array_length(coalesce(p_current_snapshot->'debts','[]'::jsonb)) + jsonb_array_length(coalesce(p_current_snapshot->'payments','[]'::jsonb)) > 0 then
    insert into public.store_backups(store_id,created_at,reason,snapshot_json,contact_count,debt_count,payment_count)
    values (p_store_id,p_now,'قبل استعادة زبون محذوف',p_current_snapshot,
      jsonb_array_length(coalesce(p_current_snapshot->'contacts','[]'::jsonb)),
      jsonb_array_length(coalesce(p_current_snapshot->'debts','[]'::jsonb)),
      jsonb_array_length(coalesce(p_current_snapshot->'payments','[]'::jsonb)));
  end if;
  delete from public.store_backups b where b.store_id=p_store_id and b.id not in (
    select recent.id from public.store_backups recent where recent.store_id=p_store_id order by recent.created_at desc,recent.id desc limit 100
  );

  insert into public.contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_by,created_at)
  values (p_store_id,contact_id,left(coalesce(contact_json->>'name',''),120),left(coalesce(contact_json->>'phone',''),40),
    left(coalesce(contact_json->>'category',''),40),left(coalesce(contact_json->>'note',''),500),coalesce((contact_json->>'whatsappOptIn')::boolean,false),
    round(coalesce((contact_json->>'creditLimit')::numeric,0)*100)::bigint,left(coalesce(contact_json->>'createdBy',''),80),coalesce((contact_json->>'createdAt')::bigint,p_now));
  select coalesce(max(id),0)+1 into next_id from public.debts where store_id=p_store_id;
  for item in select value from jsonb_array_elements(coalesce(archive_json->'debts','[]'::jsonb)) loop
    old_id := (item->>'id')::bigint; new_id := old_id;
    if exists(select 1 from public.debts where store_id=p_store_id and id=new_id) then new_id := next_id; next_id := next_id+1; end if;
    debt_map := debt_map || jsonb_build_object(old_id::text,new_id);
    insert into public.debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_by,created_at)
    values (p_store_id,new_id,contact_id,case when item->>'direction'='payable' then 'payable' else 'receivable' end,
      round((item->>'amount')::numeric*100)::bigint,left(coalesce(item->>'note',''),500),left(coalesce(item->>'dueDate',''),20),
      left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now));
  end loop;
  select coalesce(max(id),0)+1 into next_id from public.payments where store_id=p_store_id;
  for item in select value from jsonb_array_elements(coalesce(archive_json->'payments','[]'::jsonb)) loop
    old_id := (item->>'debtId')::bigint;
    if debt_map ? old_id::text then
      new_id := (item->>'id')::bigint;
      if exists(select 1 from public.payments where store_id=p_store_id and id=new_id) then new_id := next_id; next_id := next_id+1; end if;
      insert into public.payments(store_id,id,debt_id,amount_cents,method,note,created_by,created_at,server_received_at)
      values (p_store_id,new_id,(debt_map->>old_id::text)::bigint,round((item->>'amount')::numeric*100)::bigint,
        case when item->>'method' in ('cash','bank','wallet') then item->>'method' else 'cash' end,
        left(coalesce(item->>'note',''),500),left(coalesce(item->>'createdBy',''),80),coalesce((item->>'createdAt')::bigint,p_now),p_now);
    end if;
  end loop;
  update public.contact_archives set restored_at=p_now where id=p_archive_id and store_id=p_store_id;
  update public.stores set revision=revision+1 where id=p_store_id returning revision into current_revision;
  return current_revision;
end;
$$;

revoke all on function public.sadad_record_login_failure(text, integer, bigint, bigint, bigint) from public, anon, authenticated;
revoke all on function public.sadad_restore_store_backup(bigint, bigint, bigint, jsonb, bigint) from public, anon, authenticated;
revoke all on function public.sadad_restore_contact_archive(bigint, bigint, jsonb, bigint) from public, anon, authenticated;
grant execute on function public.sadad_record_login_failure(text, integer, bigint, bigint, bigint) to service_role;
grant execute on function public.sadad_restore_store_backup(bigint, bigint, bigint, jsonb, bigint) to service_role;
grant execute on function public.sadad_restore_contact_archive(bigint, bigint, jsonb, bigint) to service_role;
