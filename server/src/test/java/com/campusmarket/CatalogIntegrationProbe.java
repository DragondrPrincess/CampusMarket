package com.campusmarket;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.math.BigDecimal;

public final class CatalogIntegrationProbe {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<String> checks=new ArrayList<>();
    private record Reply(int status,JsonObject json) {JsonObject data(){return json.getAsJsonObject("data");}}
    public static void main(String[] args){try{run(Path.of(args[0]));}catch(SQLException e){System.err.println("CATALOG TEST FAIL: state="+e.getSQLState()+"; code="+e.getErrorCode());System.exit(1);}catch(AssertionError e){System.err.println("CATALOG TEST FAIL: "+e.getMessage());System.exit(1);}catch(Exception e){System.err.println("CATALOG TEST FAIL: "+e.getClass().getSimpleName());System.exit(1);}}
    private static void run(Path output)throws Exception{
        Reply categories=get("/categories");check(categories.status==200&&categories.json.getAsJsonArray("data").size()>=6,"database categories returned");
        Reply first=get("/products?page=1&pageSize=3"),second=get("/products?page=2&pageSize=3");
        check(first.status==200&&first.data().getAsJsonArray("items").size()==3,"page size applied");
        Set<Long> ids=new HashSet<>();for(JsonElement item:first.data().getAsJsonArray("items"))ids.add(item.getAsJsonObject().get("id").getAsLong());
        for(JsonElement item:second.data().getAsJsonArray("items"))check(!ids.contains(item.getAsJsonObject().get("id").getAsLong()),"pages do not overlap");
        long expected;try(Connection c=Database.open();PreparedStatement sql=c.prepareStatement("SELECT COUNT(*) FROM products p JOIN categories c ON c.id=p.category_id JOIN users u ON u.id=p.seller_id WHERE p.status='ON_SALE' AND c.enabled=1 AND u.status='ACTIVE'");ResultSet rows=sql.executeQuery()){rows.next();expected=rows.getLong(1);}
        check(first.data().get("total").getAsLong()==expected,"API total equals actual JDBC count");
        Reply search=get("/products?keyword="+URLEncoder.encode("高等数学",java.nio.charset.StandardCharsets.UTF_8));
        check(search.data().getAsJsonArray("items").size()>0,"Chinese keyword search");
        JsonObject product=search.data().getAsJsonArray("items").get(0).getAsJsonObject();long id=product.get("id").getAsLong(),category=product.get("categoryId").getAsLong();
        Reply detail=get("/products/"+id);check(detail.status==200&&detail.data().get("description").getAsString().contains("虚构"),"persistent description detail");
        check(!detail.data().has("password_hash")&&!detail.data().has("username")&&!detail.data().has("token"),"seller private credentials excluded");
        for(JsonElement item:get("/products?categoryId="+category).data().getAsJsonArray("items"))check(item.getAsJsonObject().get("categoryId").getAsLong()==category,"category filter applied");
        for(String sort:List.of("price_asc","price_desc")){BigDecimal prior=null;for(JsonElement item:get("/products?sort="+sort).data().getAsJsonArray("items")){BigDecimal price=item.getAsJsonObject().get("price").getAsBigDecimal();if(prior!=null)check(sort.equals("price_asc")?prior.compareTo(price)<=0:prior.compareTo(price)>=0,"price ordering "+sort);prior=price;}}
        check(get("/products?keyword=definitely_no_match_51f3c9").data().get("total").getAsLong()==0,"empty search result");
        check(get("/products?keyword=%25").data().get("total").getAsLong()==0,"percent treated literally");
        check(get("/products?keyword=%27%20OR%201%3D1%20--").data().get("total").getAsLong()==0,"SQL injection text treated as search data");
        check(get("/products?page=10000").data().getAsJsonArray("items").size()==0,"beyond end page empty");
        for(String suffix:List.of("?page=0","?pageSize=51","?page=x","?categoryId=-1","?sort=price%3BDROP","/abc","/0"))check(get("/products"+suffix).status==400,"invalid query or id rejected "+suffix);
        check(get("/products/999999999999999999").status==404,"unknown detail not found");
        long hidden;try(Connection c=Database.open();PreparedStatement sql=c.prepareStatement("SELECT p.id FROM products p JOIN users u ON u.id=p.seller_id WHERE u.username='p4_demo_seller' AND p.status='OFF_SHELF' LIMIT 1");ResultSet rows=sql.executeQuery()){check(rows.next(),"hidden fixture persisted");hidden=rows.getLong(1);}
        check(get("/products/"+hidden).status==404,"off shelf product not public");
        HttpResponse<String> post=HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/products")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());check(post.statusCode()==401,"phase 5 publication requires authentication");
        Files.createDirectories(output.getParent());Files.writeString(output,new Gson().toJson(Map.of("passed",true,"checks",checks,"visibleProducts",expected)));
        System.out.println("CATALOG HTTP/JDBC PASS: "+checks.size()+" checks; public product count="+expected);
    }
    private static String base(){return "http://127.0.0.1:8080/campus-market/api/v1";}
    private static Reply get(String path)throws Exception{HttpResponse<String> response=HTTP.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),JsonParser.parseString(response.body()).getAsJsonObject());}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks.add(label);}
}
