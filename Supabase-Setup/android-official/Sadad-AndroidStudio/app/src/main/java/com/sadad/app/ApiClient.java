package com.sadad.app;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    static final class Failure extends IOException {
        final int status; final String code; final long retryAfterSeconds;
        Failure(int status, String code, String message, long retryAfterSeconds) {
            super(message); this.status=status; this.code=code; this.retryAfterSeconds=retryAfterSeconds;
        }
        boolean retryable() { return status == 429 || status >= 500 || "dependency_pending".equals(code); }
    }
    static JSONObject request(String base, String bearer, String method, String path, String body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(base.replaceAll("/+$", "")+"/"+path.replaceAll("^/+", "")).openConnection();
        try {
            connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
            connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!BuildConfig.SUPABASE_PUBLISHABLE_KEY.isEmpty()) connection.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY);
            if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization","Bearer "+bearer);
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream out=connection.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
            }
            int status=connection.getResponseCode(); String response;
            try(InputStream in=status>=200&&status<300?connection.getInputStream():connection.getErrorStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                if(in!=null){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);}
                response=bytes.toString("UTF-8");
            }
            JSONObject json;
            try { json=response.isEmpty()?new JSONObject():new JSONObject(response); }
            catch(Exception malformed){throw new IOException("استجابة غير مكتملة؛ ستعاد المحاولة بنفس رقم العملية.",malformed);}
            if(status<200||status>=300) {
                long retry=0;try{retry=Long.parseLong(connection.getHeaderField("Retry-After"));}catch(Exception ignored){}
                throw new Failure(status,json.optString("code","http_"+status),json.optString("message","تعذر الاتصال بالخادم ("+status+")."),retry);
            }
            return json;
        } finally { connection.disconnect(); }
    }
}
