package com.campusmarket;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Uses real HTTP, uploaded bytes, filesystem and JDBC. Fixture records are retained, products soft-deleted. */
public final class PublishingIntegrationProbe {
    private static final String BASE="http://127.0.0.1:8080/campus-market/api/v1";
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Gson JSON=new Gson();private static final List<String> CHECKS=new ArrayList<>();
    private record Reply(int status,JsonObject json){JsonObject data(){return json.getAsJsonObject("data");}}
    public static void main(String[] args){try{run(Path.of(args[0]));}catch(AssertionError e){System.err.println("PUBLISHING FAIL: "+e.getMessage());System.exit(1);}catch(SQLException e){System.err.println("PUBLISHING SQL FAIL: state="+e.getSQLState()+"; code="+e.getErrorCode());System.exit(1);}catch(Exception e){System.err.println("PUBLISHING FAIL: "+e.getClass().getSimpleName());System.exit(1);}}
    private static void run(Path evidence)throws Exception {
        String password=UUID.randomUUID()+"A9",prefix="p5_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String a=account(prefix,password),b=account(prefix+"b",password);
        long category=call("GET","/categories",null,null).json.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsLong();
        long before=count("SELECT COUNT(*) FROM products WHERE status='ON_SALE'");
        byte[] png=image(32,24);Reply upload=upload(a,png,"image/png");check(upload.status==201,"PNG upload persisted");long image=upload.data().get("id").getAsLong();
        check(binary("/images/"+image,null).statusCode()==401,"unattached upload private");check(binary("/images/"+image,b).statusCode()==403,"other user cannot read private upload");
        HttpResponse<byte[]> original=binary("/images/"+image,a);check(original.statusCode()==200&&ImageIO.read(new ByteArrayInputStream(original.body())).getWidth()==32,"owner reads re-encoded image");
        check(upload(null,png,"image/png").status==401,"upload requires session");
        check(upload(a,"<svg/>".getBytes(StandardCharsets.UTF_8),"image/png").status==415,"disguised SVG rejected");
        check(upload(a,png,"image/svg+xml").status==415,"unsupported media rejected");
        check(upload(a,new byte[5242881],"image/png").status==413,"oversize upload rejected");
        check(upload(a,image(4097,1),"image/png").status==400,"oversize dimensions rejected before decode");
        Map<String,Object> draft=draft(category,List.of(image));
        check(call("POST","/products",null,draft).status==401,"publication requires session");
        check(call("POST","/products",b,draft).status==403,"foreign image cannot be published");
        for(String price:List.of("-1","1.001","1e3","100000000.00","NaN")){Map<String,Object> invalid=new LinkedHashMap<>(draft);invalid.put("price",price);check(call("POST","/products",a,invalid).status==400,"invalid price rejected "+price);}
        Map<String,Object> invalid=new LinkedHashMap<>(draft);invalid.put("categoryId",999999999);check(call("POST","/products",a,invalid).status==400,"unknown category rejected");
        invalid=new LinkedHashMap<>(draft);invalid.put("imageIds",List.of(image,image));check(call("POST","/products",a,invalid).status==400,"duplicate image rejected");
        invalid=new LinkedHashMap<>(draft);invalid.put("title","");check(call("POST","/products",a,invalid).status==400,"empty title rejected");
        invalid=new LinkedHashMap<>(draft);invalid.put("imageIds",true);check(call("POST","/products",a,invalid).status==400,"wrong image array type rejected with JSON");
        invalid=new LinkedHashMap<>(draft);invalid.put("imageIds",Arrays.asList((Object)null));check(call("POST","/products",a,invalid).status==400,"null image entry rejected");
        invalid=new LinkedHashMap<>(draft);invalid.put("imageIds",List.of(image,image,image,image,image,image));check(call("POST","/products",a,invalid).status==400,"six images rejected");
        draft.put("sellerId",999999999);draft.put("status","SOLD");
        Reply published=call("POST","/products",a,draft);check(published.status==201,"product published");long id=published.data().get("id").getAsLong();String path="/products/"+id;
        try{
            check(count("SELECT COUNT(*) FROM products WHERE id="+id+" AND title='阶段五虚构验收商品' AND price=12.34 AND status='ON_SALE'")==1,"actual JDBC product persistence");
            check(count("SELECT COUNT(*) FROM product_images WHERE id="+image+" AND product_id="+id+" AND active=1")==1,"actual JDBC image binding");
            check(published.data().get("sellerId").getAsLong()==call("GET","/users/me",a,null).data().get("id").getAsLong(),"client supplied seller and status ignored");
            check(call("GET",path,null,null).data().getAsJsonArray("images").size()==1,"public detail images returned");
            check(binary("/images/"+image,null).statusCode()==200,"attached on-sale image public");
            check(binary("/images/../../conf/server.xml",null).statusCode()!=200,"image path traversal denied");
            check(call("GET","/users/me/products",null,null).status==401,"my listings require login");
            check(call("GET","/users/me/products",a,null).data().get("total").getAsLong()==1,"own listing present");
            check(call("GET","/users/me/products",b,null).data().get("total").getAsLong()==0,"own listing isolated");
            check(call("GET","/users/me/products/"+id,b,null).status==404,"foreign management detail denied");
            check(call("PUT",path,b,draft).status==403,"foreign edit denied");
            check(call("PUT",path+"/status",b,Map.of("status","OFF_SHELF")).status==403,"foreign off shelf denied");
            check(call("DELETE",path,b,null).status==403,"foreign delete denied");
            check(call("POST","/products",a,draft).status==400,"bound image cannot attach second product");
            check(call("PUT","/favorites/"+id,null,null).status==401,"favorites require session");
            check(call("PUT","/favorites/"+id,b,null).data().get("active").getAsBoolean(),"favorite persisted");
            check(call("PUT","/favorites/"+id,b,null).status==200,"repeated favorite idempotent");
            check(count("SELECT COUNT(*) FROM favorites WHERE product_id="+id+" AND active=1")==1,"favorite unique in JDBC");
            check(call("GET","/users/me/favorites",b,null).data().get("total").getAsLong()==1,"favorite list actual product");
            check(!call("GET","/favorites/"+id,a,null).data().get("active").getAsBoolean(),"favorite state isolated");
            draft.put("title","阶段五修改后虚构商品");draft.put("price","23.45");
            check(call("PUT",path,a,draft).status==200,"owner can edit with existing image");
            check(call("GET",path,null,null).data().get("price").getAsString().equals("23.45"),"edit survives new HTTP read");
            check(count("SELECT COUNT(*) FROM products WHERE id="+id+" AND price=23.45")==1,"edit persisted in JDBC");
            check(call("PUT",path+"/status",a,Map.of("status","SOLD")).status==400,"unsupported status rejected");
            check(call("PUT",path+"/status",a,Map.of("status","OFF_SHELF")).status==200,"owner off shelf");
            check(call("GET",path,null,null).status==404,"off shelf hidden public");check(binary("/images/"+image,null).statusCode()==401,"off shelf photo private");
            check(call("GET","/users/me/products/"+id,a,null).status==200,"owner can manage off shelf");
            check(call("GET","/users/me/favorites",b,null).data().get("total").getAsLong()==0,"off shelf excluded from favorite display");
            check(call("PUT","/favorites/"+id,b,null).status==404,"cannot newly favorite hidden product");
            check(call("PUT",path+"/status",a,Map.of("status","ON_SALE")).status==200,"owner relist");
            check(call("GET",path,null,null).status==200,"relisted visible");
            check(!call("DELETE","/favorites/"+id,b,null).data().get("active").getAsBoolean(),"unfavorite");
            check(call("DELETE","/favorites/"+id,b,null).status==200,"unfavorite idempotent");
            check(count("SELECT COUNT(*) FROM favorites WHERE product_id="+id+" AND active=0")==1,"unfavorite retains record");
            long another=upload(a,png,"image/png").data().get("id").getAsLong();draft.put("imageIds",List.of(another));
            check(call("PUT",path,a,draft).status==200,"image replacement");check(binary("/images/"+image,a).statusCode()==404,"removed image no longer served");
            check(count("SELECT COUNT(*) FROM product_images WHERE id="+image+" AND active=0")==1,"old image record retained");
            check(call("DELETE",path,a,null).status==200,"owner soft delete");
            check(call("GET",path,null,null).status==404,"deleted hidden");
            check(call("GET","/users/me/products",a,null).data().get("total").getAsLong()==0,"deleted excluded from own list");
            check(call("PUT",path,a,draft).status==409,"deleted product cannot edit");
            check(call("PUT",path+"/status",a,Map.of("status","ON_SALE")).status==409,"deleted product cannot relist");
            check(binary("/images/"+another,a).statusCode()==404,"deleted image not served");
            check(count("SELECT COUNT(*) FROM products WHERE id="+id+" AND status='DELETED'")==1,"soft deletion retains product");
            try(var files=Files.list(Path.of(System.getenv("CAMPUS_UPLOAD_DIR")))){check(files.findAny().isPresent(),"stored files retained");}
            call("POST","/auth/logout",a,null);check(call("POST","/products",a,draft).status==401,"revoked session cannot publish");
            call("POST","/auth/logout",b,null);
        }finally{try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("UPDATE products SET status='DELETED' WHERE id=?")){s.setLong(1,id);s.executeUpdate();}}
        check(count("SELECT COUNT(*) FROM products WHERE status='ON_SALE'")==before,"preexisting public count preserved");
        Files.createDirectories(evidence.getParent());Files.writeString(evidence,JSON.toJson(Map.of("passed",true,"checks",CHECKS,"fixtureProductId",id,"recordsRetained",true)));
        System.out.println("PUBLISHING HTTP/JDBC PASS: "+CHECKS.size()+" checks; no existing records deleted.");
    }
    private static Map<String,Object> draft(long category,List<Long> images){Map<String,Object> d=new LinkedHashMap<>();d.put("title","阶段五虚构验收商品");d.put("description","仅用于真实接口与数据库验收的虚构记录");d.put("price","12.34");d.put("categoryId",category);d.put("conditionLabel","九成新");d.put("tradeLocation","虚构校园图书馆");d.put("imageIds",images);return d;}
    private static String account(String user,String password)throws Exception {check(call("POST","/auth/register",null,Map.of("username",user,"password",password,"confirmPassword",password,"nickname","阶段五虚构验收用户")).status==201,"test user registered");return call("POST","/auth/login",null,Map.of("username",user,"password",password)).data().get("token").getAsString();}
    private static Reply call(String method,String path,String token,Object body)throws Exception {HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(20));if(token!=null)r.header("Authorization","Bearer "+token);r.header("Content-Type","application/json");HttpResponse<String> response=HTTP.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),JsonParser.parseString(response.body()).getAsJsonObject());}
    private static Reply upload(String token,byte[] bytes,String type)throws Exception {String boundary="test"+UUID.randomUUID();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../anything.png\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(BASE+"/images")).timeout(Duration.ofSeconds(20)).header("Content-Type","multipart/form-data; boundary="+boundary);if(token!=null)r.header("Authorization","Bearer "+token);HttpResponse<String> response=HTTP.send(r.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),JsonParser.parseString(response.body()).getAsJsonObject());}
    private static HttpResponse<byte[]> binary(String path,String token)throws Exception {HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(10));if(token!=null)r.header("Authorization","Bearer "+token);return HTTP.send(r.GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    private static byte[] image(int width,int height)throws IOException {BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    private static long count(String sql)throws SQLException {try(Connection c=Database.open();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);CHECKS.add(label);}
}
