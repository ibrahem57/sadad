-- This event-trigger helper is invoked by PostgreSQL's DDL event system, not by API clients.
REVOKE EXECUTE ON FUNCTION public.rls_auto_enable()
  FROM PUBLIC, anon, authenticated, service_role;
