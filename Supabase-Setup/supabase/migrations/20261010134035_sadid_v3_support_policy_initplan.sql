-- جعل قراءة متغير جلسة الدعم خطة ثابتة للطلب.
alter policy audited_support_insert on sadid_private.admin_access_events
with check(actor=(select sadid_private.admin_session_username((select current_setting('sadid.support.admin_hash',true)))) and store_id=(select sadid_private.support_store()));
