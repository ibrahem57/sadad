package com.sadad.app;
final class WhatsAppPhone {
 static String normalize(String value,String prefix){
  if(!prefix.equals("970")&&!prefix.equals("972"))throw new IllegalArgumentException("اختر مقدمة فلسطين أو إسرائيل.");
  StringBuilder clean=new StringBuilder();for(char c:value.trim().toCharArray()){int digit=Character.digit(c,10);if(digit>=0)clean.append(digit);else if(c!='+'&&c!='-'&&c!='('&&c!=')'&&!Character.isWhitespace(c))throw new IllegalArgumentException("أدخل رقم واتساب صحيحًا.");}
  String number=clean.toString();if(number.startsWith("00"))number=number.substring(2);
  if(number.startsWith("970")||number.startsWith("972")){if(!number.startsWith(prefix))throw new IllegalArgumentException("مقدمة الرقم لا تطابق الاختيار.");number=number.substring(3);}
  if(number.startsWith("0"))number=number.substring(1);
  if(!number.matches("[1-9][0-9]{8}"))throw new IllegalArgumentException("أدخل رقم الجوال من 9 أرقام بعد المقدمة، أو 10 أرقام تبدأ بصفر.");
  return "+"+prefix+number;
 }
}
