-- Android Auth sessions remain bound to a server-authorized store and device session.
create table public.sadad_auth_bindings (
  session_hash text primary key check (length(session_hash) = 64),
  user_id uuid not null references auth.users(id) on delete cascade,
  auth_session_id uuid not null,
  store_id bigint not null references public.stores(id) on delete cascade,
  expires_at bigint not null,
  created_at timestamptz not null default now()
);
create index sadad_auth_bindings_user_idx on public.sadad_auth_bindings(user_id);
create index sadad_auth_bindings_store_idx on public.sadad_auth_bindings(store_id);
create index sadad_auth_bindings_expiry_idx on public.sadad_auth_bindings(expires_at);
alter table public.sadad_auth_bindings enable row level security;
revoke all on public.sadad_auth_bindings from public, anon, authenticated;
grant select, insert, update, delete on public.sadad_auth_bindings to service_role;
-- No client policies: the gateway checks both Auth and the existing store/device session.
