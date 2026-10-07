package com.sadad.app;
import android.app.DatePickerDialog;
import android.content.Context;
final class SadadDatePickerDialog extends DatePickerDialog {
    SadadDatePickerDialog(Context context, OnDateSetListener listener, int year, int month, int day) { super(SadadDialog.context(context), SadadDialog.theme(context), listener, year, month, day); }
    @Override protected void onStart() { super.onStart(); SadadDialog.decorate(this, true); }
}
