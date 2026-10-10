-- V3 is additive. The old ledger is retained for explicitly reconciled imports.
alter table public.stores add column ledger_version integer not null default 1 check (ledger_version in (1,3));
alter table public.stores add column archived_at bigint;
alter table public.stores add column archived_by text;
alter table public.stores add column archive_reason text;
update public.stores s set ledger_version=3 where not exists(select 1 from public.contacts c where c.store_id=s.id)
  and not exists(select 1 from public.debts d where d.store_id=s.id)
  and not exists(select 1 from public.payments p where p.store_id=s.id);
alter table public.stores alter column ledger_version set default 3;

create table public.ledger_state (
  store_id bigint primary key references public.stores(id) on delete restrict,
  sequence bigint not null default 0 check(sequence>=0),
  epoch uuid not null default gen_random_uuid(),
  currency text not null default 'ILS' check(currency='ILS')
);
insert into public.ledger_state(store_id) select id from public.stores;

create table public.ledger_contacts (
  store_id bigint not null references public.stores(id) on delete restrict,
  id uuid not null, name text not null check(length(trim(name)) between 1 and 120),
  phone text not null default '' check(length(phone)<=40), category text not null default '' check(length(category)<=40),
  note text not null default '' check(length(note)<=2000), whatsapp_opt_in boolean not null default false,
  credit_limit_cents bigint not null default 0 check(credit_limit_cents between 0 and 100000000000),
  version bigint not null default 1, created_at bigint not null, received_at bigint not null,
  created_by text not null, archived_at bigint, archived_by text, archive_reason text,
  primary key(store_id,id)
);
create table public.ledger_debts (
  store_id bigint not null, id uuid not null, contact_id uuid not null,
  direction text not null check(direction in ('receivable','payable')),
  original_amount_cents bigint not null check(original_amount_cents between 1 and 100000000000),
  amount_cents bigint not null check(amount_cents between 0 and 100000000000),
  note text not null default '' check(length(note)<=2000), due_date text not null default '',
  version bigint not null default 1, created_at bigint not null, received_at bigint not null,
  created_by text not null, primary key(store_id,id),
  foreign key(store_id,contact_id) references public.ledger_contacts(store_id,id) on delete restrict
);
create index ledger_debts_contact on public.ledger_debts(store_id,contact_id);
create table public.ledger_payments (
  store_id bigint not null, id uuid not null, debt_id uuid not null,
  amount_cents bigint not null check(amount_cents between 1 and 100000000000),
  method text not null check(method in ('cash','bank','wallet')),
  note text not null default '' check(length(note)<=2000),
  created_at bigint not null, received_at bigint not null, created_by text not null,
  primary key(store_id,id),
  foreign key(store_id,debt_id) references public.ledger_debts(store_id,id) on delete restrict
);
create index ledger_payments_debt on public.ledger_payments(store_id,debt_id);
create table public.ledger_reversals (
  store_id bigint not null, id uuid not null, payment_id uuid not null,
  reason text not null check(length(trim(reason)) between 1 and 2000),
  created_at bigint not null, received_at bigint not null, created_by text not null,
  primary key(store_id,id), unique(store_id,payment_id),
  foreign key(store_id,payment_id) references public.ledger_payments(store_id,id) on delete restrict
);
create table public.ledger_operations (
  store_id bigint not null references public.stores(id) on delete restrict,
  operation_id uuid not null, request jsonb not null, result jsonb not null,
  device_id text not null, actor text not null, received_at bigint not null,
  primary key(store_id,operation_id)
);
create table public.ledger_events (
  store_id bigint not null references public.stores(id) on delete restrict,
  sequence bigint not null, operation_id uuid, type text not null, entity_id uuid,
  contact_id uuid, debt_id uuid, payment_id uuid,
  actor text not null, device_id text not null,
  occurred_at bigint not null, received_at bigint not null,
  before_data jsonb, after_data jsonb, command jsonb not null,
  primary key(store_id,sequence)
);
create index ledger_events_contact on public.ledger_events(store_id,contact_id,sequence);
create table public.ledger_changes (
  store_id bigint not null references public.stores(id) on delete restrict,
  sequence bigint not null, operation_id uuid, type text not null, entity_id uuid,
  received_at bigint not null, primary key(store_id,sequence)
);
create table public.ledger_account_events (
  id bigint generated always as identity primary key,
  store_id bigint not null references public.stores(id) on delete restrict,
  kind text not null, actor text not null default '', occurred_at bigint not null,
  before_data jsonb, after_data jsonb
);
create index ledger_account_events_store on public.ledger_account_events(store_id,id);

create function public.sadad_history_immutable() returns trigger language plpgsql set search_path='' as $$
begin raise exception using errcode='P0001',message='history_is_immutable'; end;
$$;
create function public.sadad_block_legacy_write() returns trigger language plpgsql set search_path='' as $$
declare v_store bigint;
begin
  v_store := case when tg_op='DELETE' then old.store_id else new.store_id end;
  if exists(select 1 from public.stores where id=v_store and ledger_version=3) then
    raise exception using errcode='P0001',message='legacy_ledger_read_only';
  end if;
  return case when tg_op='DELETE' then old else new end;
end; $$;
create trigger contacts_legacy_guard before insert or update or delete on public.contacts for each row execute function public.sadad_block_legacy_write();
create trigger debts_legacy_guard before insert or update or delete on public.debts for each row execute function public.sadad_block_legacy_write();
create trigger payments_legacy_guard before insert or update or delete on public.payments for each row execute function public.sadad_block_legacy_write();

create function public.sadad_account_history() returns trigger language plpgsql security definer set search_path='' as $$
declare v_old jsonb; v_new jsonb; v_store bigint; v_actor text;
begin
  if tg_table_name='stores' then
    v_store:=new.id;
    if tg_op='INSERT' then insert into public.ledger_state(store_id) values(new.id); end if;
    if tg_op='UPDATE' and old.ledger_version=3 and new.ledger_version<>3 then
      raise exception using errcode='P0001',message='ledger_downgrade_forbidden';
    end if;
  else v_store:=new.store_id; end if;
  v_old:=case when tg_op='UPDATE' then to_jsonb(old)-array['salt','password_hash','whatsapp_token_enc'] else null end;
  v_new:=to_jsonb(new)-array['salt','password_hash','whatsapp_token_enc'];
  if v_old is distinct from v_new then
    v_actor:=coalesce(nullif(current_setting('sadad.actor',true),''),'administration');
    insert into public.ledger_account_events(store_id,kind,actor,occurred_at,before_data,after_data)
    values(v_store,tg_table_name||'.'||lower(tg_op),v_actor,(extract(epoch from clock_timestamp())*1000)::bigint,v_old,v_new);
  end if;
  return new;
end; $$;
create trigger stores_history after insert or update on public.stores for each row execute function public.sadad_account_history();
create trigger devices_history after insert or update on public.store_devices for each row execute function public.sadad_account_history();
create trigger stores_no_delete before delete on public.stores for each row execute function public.sadad_history_immutable();
create trigger devices_no_delete before delete on public.store_devices for each row execute function public.sadad_history_immutable();

do $$ declare t text; begin
  foreach t in array array['ledger_state','ledger_contacts','ledger_debts','ledger_payments','ledger_reversals','ledger_operations','ledger_events','ledger_changes','ledger_account_events'] loop
    execute format('alter table public.%I enable row level security',t);
    execute format('revoke all on public.%I from public, anon, authenticated, service_role',t);
    execute format('grant select on public.%I to service_role',t);
    execute format('create trigger %I before delete on public.%I for each row execute function public.sadad_history_immutable()',t||'_no_delete',t);
  end loop;
  foreach t in array array['ledger_payments','ledger_reversals','ledger_operations','ledger_events','ledger_changes','ledger_account_events'] loop
    execute format('create trigger %I before update on public.%I for each row execute function public.sadad_history_immutable()',t||'_no_update',t);
  end loop;
end; $$;
revoke all on function public.sadad_history_immutable(), public.sadad_block_legacy_write(), public.sadad_account_history() from public,anon,authenticated,service_role;

-- The view stays service-only; monetary arithmetic runs inside Postgres.
create view public.ledger_debt_balances with (security_invoker=true) as
select d.*, coalesce(p.paid_cents,0)::bigint paid_cents,
 greatest(0,d.amount_cents-coalesce(p.paid_cents,0))::bigint remaining_cents,
 greatest(0,coalesce(p.paid_cents,0)-d.amount_cents)::bigint credit_cents
from public.ledger_debts d left join lateral (
  select sum(p.amount_cents) paid_cents from public.ledger_payments p
  where p.store_id=d.store_id and p.debt_id=d.id
    and not exists(select 1 from public.ledger_reversals r where r.store_id=p.store_id and r.payment_id=p.id)
) p on true;
revoke all on public.ledger_debt_balances from public,anon,authenticated;
grant select on public.ledger_debt_balances to service_role;
