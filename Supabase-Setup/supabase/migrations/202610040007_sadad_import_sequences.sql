-- Reset identity sequences after the one-time SQLite import preserves legacy IDs.

create or replace function public.sadad_sync_identity_sequences()
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  max_id bigint;
  sequence_name text;
begin
  select coalesce(max(id),0) into max_id from public.admins;
  sequence_name := pg_get_serial_sequence('public.admins','id');
  perform setval(sequence_name::regclass, greatest(max_id + 1, 1), false);

  select coalesce(max(id),0) into max_id from public.stores;
  sequence_name := pg_get_serial_sequence('public.stores','id');
  perform setval(sequence_name::regclass, greatest(max_id + 1, 1), false);

  select coalesce(max(id),0) into max_id from public.audit_events;
  sequence_name := pg_get_serial_sequence('public.audit_events','id');
  perform setval(sequence_name::regclass, greatest(max_id + 1, 1), false);

  select coalesce(max(id),0) into max_id from public.store_backups;
  sequence_name := pg_get_serial_sequence('public.store_backups','id');
  perform setval(sequence_name::regclass, greatest(max_id + 1, 1), false);

  select coalesce(max(id),0) into max_id from public.contact_archives;
  sequence_name := pg_get_serial_sequence('public.contact_archives','id');
  perform setval(sequence_name::regclass, greatest(max_id + 1, 1), false);
end;
$$;

revoke all on function public.sadad_sync_identity_sequences() from public, anon, authenticated;
grant execute on function public.sadad_sync_identity_sequences() to service_role;
