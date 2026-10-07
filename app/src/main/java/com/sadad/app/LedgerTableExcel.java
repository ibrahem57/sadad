package com.sadad.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free XLSX workbook: one RTL, numeric, filterable ledger per person. */
final class LedgerTableExcel {
    static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static byte[] create(String store, String owner, String period, JSONObject data, long personId, long from, long to) throws Exception {
        JSONArray people = data.optJSONArray("contacts"), transactions = data.optJSONArray("transactions"), debts = data.optJSONArray("debts");
        ArrayList<String> sheets = new ArrayList<>(), names = new ArrayList<>(); Set<String> used = new HashSet<>();
        Map<Long,String> dueDates = new HashMap<>();
        if (debts != null) for (int i=0;i<debts.length();i++) { JSONObject debt=debts.optJSONObject(i); if(debt!=null) dueDates.put(debt.optLong("id"),debt.optString("dueDate")); }
        if (people != null) for(int i=0;i<people.length();i++) {
            JSONObject person=people.optJSONObject(i); if(person==null || personId!=0 && person.optLong("id")!=personId)continue;
            String base=person.optString("name","شخص").replaceAll("[\\\\/\\[\\]:*?]","-").replace("'", "").trim(); if(base.isEmpty())base="شخص"; if(base.length()>25)base=base.substring(0,25);
            String name=base; int suffix=2; while(!used.add(name.toLowerCase(Locale.ROOT)))name=base+" ("+(suffix++)+")"; names.add(name);
            ArrayList<JSONObject> rows=new ArrayList<>();
            if(transactions!=null)for(int t=0;t<transactions.length();t++){JSONObject tx=transactions.optJSONObject(t);if(tx!=null&&tx.optLong("contactId")==person.optLong("id")&&"receivable".equals(tx.optString("direction")))rows.add(tx);}
            rows.sort((a,b)->{int c=Long.compare(a.optLong("createdAt"),b.optLong("createdAt"));return c!=0?c:Long.compare(a.optLong("id"),b.optLong("id"));});
            long opening=0;for(JSONObject tx:rows)if(tx.optLong("createdAt")<from)opening+=("payment".equals(tx.optString("kind"))?-1:1)*Math.round(tx.optDouble("amount")*100);
            StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""+NS+"\"><sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"1\"><pane ySplit=\"6\" topLeftCell=\"A7\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"1\" width=\"23\" customWidth=\"1\"/><col min=\"2\" max=\"6\" width=\"18\" customWidth=\"1\"/><col min=\"7\" max=\"7\" width=\"48\" customWidth=\"1\"/></cols><sheetData>");
            row(xml,1,new String[]{"المتجر: "+store,"صاحب المتجر: "+owner},false);
            row(xml,2,new String[]{"اسم الشخص: "+person.optString("name"),"الهاتف: "+person.optString("phone")},false);
            row(xml,3,new String[]{"الفترة: "+period},false);
            row(xml,4,new String[]{"الرصيد الافتتاحي",null,null,null,number(opening)},true);
            row(xml,5,new String[]{"ملاحظات الشخص: "+person.optString("note")},false);
            row(xml,6,new String[]{"التاريخ","الحركة","دين ₪","دفعة ₪","الرصيد ₪","الاستحقاق","البيان / المسجل"},false);
            int line=7;long running=opening,totalDebt=0,totalPaid=0;
            for(JSONObject tx:rows){long time=tx.optLong("createdAt");if(time<from||time>to)continue;
                boolean paid="payment".equals(tx.optString("kind"));long cents=Math.round(tx.optDouble("amount")*100);running+=paid?-cents:cents;if(paid)totalPaid+=cents;else totalDebt+=cents;
                String note=tx.optString("note");if(!tx.optString("createdBy").trim().isEmpty())note+=(note.isEmpty()?"":"\n")+"سجّلها: "+tx.optString("createdBy");
                row(xml,line++,new String[]{new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(time)),paid?"دفعة":"دين",paid?null:number(cents),paid?number(cents):null,number(running),paid?"":dueDates.getOrDefault(tx.optLong("debtId"),tx.optString("dueDate")),note},true);
            }
            int last=line-1;
            row(xml,line,new String[]{"","الإجمالي",number(totalDebt),number(totalPaid),number(running),"","رصيد نهاية الفترة"},true);
            xml.append("</sheetData>");if(last>=7)xml.append("<autoFilter ref=\"A6:G").append(last).append("\"/>");
            xml.append("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/></worksheet>");sheets.add(xml.toString());
        }
        if(sheets.isEmpty())throw new IllegalArgumentException("لا توجد بيانات");
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){
            StringBuilder types=new StringBuilder("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder book=new StringBuilder("<?xml version=\"1.0\"?><workbook xmlns=\""+NS+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rel=new StringBuilder("<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for(int i=0;i<sheets.size();i++){int id=i+1;types.append("<Override PartName=\"/xl/worksheets/sheet").append(id).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");book.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(id).append("\" r:id=\"rId").append(id).append("\"/>");rel.append("<Relationship Id=\"rId").append(id).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(id).append(".xml\"/>");put(zip,"xl/worksheets/sheet"+id+".xml",sheets.get(i));}
            rel.append("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");book.append("</sheets></workbook>");types.append("</Types>");
            put(zip,"[Content_Types].xml",types.toString());put(zip,"xl/workbook.xml",book.toString());put(zip,"xl/_rels/workbook.xml.rels",rel.toString());
            put(zip,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"workbook\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip,"xl/styles.xml","<?xml version=\"1.0\"?><styleSheet xmlns=\""+NS+"\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00 &amp;quot;₪&amp;quot;\"/></numFmts><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF064B42\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment horizontal=\"right\" vertical=\"top\" wrapText=\"1\"/></xf><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\"><alignment horizontal=\"right\" wrapText=\"1\"/></xf><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"><alignment horizontal=\"right\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>".replace("&amp;quot;","&quot;"));
        }return output.toByteArray();
    }
    private static String number(long cents){return java.math.BigDecimal.valueOf(cents,2).toPlainString();}
    private static void row(StringBuilder xml,int row,String[] cells,boolean numeric){xml.append("<row r=\"").append(row).append("\">");for(int i=0;i<cells.length;i++){if(cells[i]==null)continue;String ref=""+(char)('A'+i)+row;boolean number=numeric&&i>=2&&i<=4;xml.append("<c r=\"").append(ref).append("\" s=\"").append(row==6?1:number?2:0).append("\"");if(number)xml.append("><v>").append(cells[i]).append("</v></c>");else xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cells[i])).append("</t></is></c>");}xml.append("</row>");}
    private static String escape(String s){return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static void put(ZipOutputStream zip,String name,String value)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
