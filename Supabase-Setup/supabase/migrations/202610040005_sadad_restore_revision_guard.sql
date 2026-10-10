-- Serialize admin restore with mobile sync and ensure the snapshot stored as
-- the pre-restore backup matches the revision being replaced.

create or replace function public.sadad_restore_store_backup_checked(
  p_store_id bigint,
  p_backup_id bigint,
  p_contact_id bigint,
  p_expected_revision bigint,
  p_current_snapshot jsonb,
  p_now bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare current_revision bigint;
begin
  select revision into current_revision from public.stores where id=p_store_id and status <> 'deleted' for update;
  if current_revision is null then raise exception using errcode='P0001',message='store_not_found'; end if;
  if current_revision <> p_expected_revision then raise exception using errcode='P0001',message='revision_conflict'; end if;
  if coalesce((p_current_snapshot->>'revision')::bigint, -1) <> p_expected_revision then
    raise exception using errcode='P0001',message='snapshot_revision_mismatch';
  end if;
  return public.sadad_restore_store_backup(p_store_id,p_backup_id,p_contact_id,p_current_snapshot,p_now);
end;
$$;

create or replace function public.sadad_restore_contact_archive_checked(
  p_store_id bigint,
  p_archive_id bigint,
  p_expected_revision bigint,
  p_current_snapshot jsonb,
  p_now bigint
)
returns bigint
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare current_revision bigint;
begin
  select revision into current_revision from public.stores where id=p_store_id and status <> 'deleted' for update;
  if current_revision is null then raise exception using errcode='P0001',message='store_not_found'; end if;
  if current_revision <> p_expected_revision then raise exception using errcode='P0001',message='revision_conflict'; end if;
  if coalesce((p_current_snapshot->>'revision')::bigint, -1) <> p_expected_revision then
    raise exception using errcode='P0001',message='snapshot_revision_mismatch';
  end if;
  return public.sadad_restore_contact_archive(p_store_id,p_archive_id,p_current_snapshot,p_now);
end;
$$;

revoke all on function public.sadad_restore_store_backup_checked(bigint,bigint,bigint,bigint,jsonb,bigint) from public, anon, authenticated;
revoke all on function public.sadad_restore_contact_archive_checked(bigint,bigint,bigint,jsonb,bigint) from public, anon, authenticated;
grant execute on function public.sadad_restore_store_backup_checked(bigint,bigint,bigint,bigint,jsonb,bigint) to service_role;
grant execute on function public.sadad_restore_contact_archive_checked(bigint,bigint,bigint,jsonb,bigint) to service_role;
