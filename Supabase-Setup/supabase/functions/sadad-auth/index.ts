import { createClient } from "supabase";
import { makeHandler } from "./handler.ts";

const projectUrl = Deno.env.get("SUPABASE_URL");
const serverKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!projectUrl || !serverKey) throw new Error("Missing Supabase environment");
const client = () =>
  createClient(projectUrl, serverKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
  });
Deno.serve(
  makeHandler({ db: client(), newAuthClient: client, projectUrl, serverKey }),
);
