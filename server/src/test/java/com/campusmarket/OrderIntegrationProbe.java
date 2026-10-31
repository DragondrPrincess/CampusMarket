package com.campusmarket;

import com.campusmarket.service.AuthService;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Real Tomcat/MySQL state-machine, authorization and concurrent transaction tests. */
public final class OrderIntegrationProbe {
    private static final String BASE="http://127.0.0.1:8080/campus-market/api/v1";
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Gson JSON=new Gson();private static final List<String> CHECKS=new ArrayList<>();
    private static final List<Long> PRODUCTS=new ArrayList<>();private static final Map<Long,String> BUYERS=new HashMap<>();
    private static String seller,buyer,other,outsider;private static long category;
    private record Reply(int status,JsonObject json){JsonObject data(){return json.getAsJsonObject("data");}long id(){return data().get("id").getAsLong();}}
    private record Request(String method,String path,String token,Object body){}
    public static void main(String[] args){try{run(Path.of(args[0]));}catch(AssertionError e){System.err.println("ORDERS FAIL: "+e.getMessage());System.exit(1);}catch(SQLException e){System.err.println("ORDERS SQL FAIL: state="+e.getSQLState()+"; code="+e.getErrorCode());System.exit(1);}catch(Exception e){System.err.println("ORDERS FAIL: "+e.getClass().getSimpleName());System.exit(1);}}
    private static void run(Path evidence)throws Exception {
        long before=count("SELECT COUNT(*) FROM products WHERE status='ON_SALE'");
        String prefix="p6_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        seller=account(prefix+"s");buyer=account(prefix+"b");other=account(prefix+"o");outsider=account(prefix+"x");
        category=call("GET","/categories",null,null).json.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsLong();
        try {
            basicAndRefund();completeFlow();unpaidCancel();concurrentBuyers();sameRequestRace();crossProductKeyRace();paymentCancelRace();deliveryCancelRace();saleStateRace();
            for(String q:List.of("?role=admin","?page=0","?pageSize=51","?page=x"))status(call("GET","/orders"+q,buyer,null),400,"invalid order list parameters");
            status(call("GET","/orders/abc",buyer,null),400,"invalid order id");status(call("GET","/orders/999999999999999999",buyer,null),404,"missing order");
            status(call("POST","/orders",buyer,Map.of("productId",1,"requestId","invalid")),400,"invalid request UUID");
            status(call("POST","/orders",buyer,Map.of("productId",-1,"requestId",key())),400,"invalid product id");
            status(call("POST","/orders",null,Map.of()),401,"unauthenticated create");status(call("GET","/orders",null,null),401,"unauthenticated list");
            status(call("GET","/orders",buyer,null),200,"default buyer list");
            long mine=me(buyer);check(call("GET","/orders?role=buyer",buyer,null).data().get("total").getAsLong()==count("SELECT COUNT(*) FROM orders WHERE buyer_id="+mine),"buyer page total matches actual JDBC");
            Reply page1=call("GET","/orders?role=buyer&pageSize=1&page=1",buyer,null),page2=call("GET","/orders?role=buyer&pageSize=1&page=2",buyer,null);
            check(page1.data().getAsJsonArray("items").size()==1&&page2.data().getAsJsonArray("items").size()==1&&page1.data().getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsLong()!=page2.data().getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsLong(),"order pagination without overlap");
            try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("UPDATE auth_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE token_hash=?")){
                s.setString(1,AuthService.tokenHash(outsider));check(s.executeUpdate()==1,"expire only this fixture session");
            }
            Reply expired=call("POST","/orders",outsider,createBody(PRODUCTS.get(0),key()));status(expired,401,"expired token cannot order");check(expired.json.get("code").getAsInt()==40102,"expired order request has distinct error code");
            assertInvariants();
        } finally {cleanup();}
        check(count("SELECT COUNT(*) FROM products WHERE status='ON_SALE'")==before,"preexisting public product count retained");
        call("POST","/auth/logout",buyer,null);status(call("GET","/orders",buyer,null),401,"revoked token cannot list orders");
        call("POST","/auth/logout",other,null);call("POST","/auth/logout",seller,null);call("POST","/auth/logout",outsider,null);
        Files.createDirectories(evidence.getParent());Files.writeString(evidence,JSON.toJson(Map.of("passed",true,"checks",CHECKS,"fixtureProductIds",PRODUCTS,"concurrentRequestsPerBatch",8,"existingDataRetained",true)));
        System.out.println("ORDERS HTTP/JDBC PASS: "+CHECKS.size()+" checks; concurrent batches up to 8 requests; records retained.");
    }
    private static void basicAndRefund()throws Exception {
        long p=product(),p2=product();String request=key();
        status(create(seller,p,key()),403,"self purchase denied");
        call("PUT","/products/"+p+"/status",seller,Map.of("status","OFF_SHELF"));status(create(buyer,p,key()),409,"off shelf cannot order");call("PUT","/products/"+p+"/status",seller,Map.of("status","ON_SALE"));
        Map<String,Object> body=new LinkedHashMap<>(createBody(p,request));body.put("amount","0.01");body.put("buyerId",999);body.put("sellerId",999);body.put("status","COMPLETED");
        Reply made=call("POST","/orders",buyer,body);status(made,201,"order created");long id=made.id();state(made,"PENDING_CONFIRMATION","RESERVED","UNPAID");
        check(made.data().get("amount").getAsString().equals("18.80"),"amount taken from database not client");
        check(made.data().get("buyerId").getAsLong()==me(buyer),"buyer identity from session");
        check(count("SELECT COUNT(*) FROM orders WHERE id="+id+" AND amount=18.80 AND product_id="+p)==1,"actual JDBC order persistence");
        status(call("GET","/products/"+p,null,null),404,"reserved product hidden public");
        status(call("PUT","/products/"+p,seller,draft()),409,"reserved product cannot edit");status(call("DELETE","/products/"+p,seller,null),409,"reserved product cannot delete");
        status(call("PUT","/products/"+p+"/status",seller,Map.of("status","OFF_SHELF")),409,"reserved product cannot off shelf");
        Reply replay=create(buyer,p,request);status(replay,200,"idempotent create replay");check(replay.id()==id,"same request same order");
        status(create(buyer,p,key()),409,"same buyer new request cannot duplicate");status(create(other,p,key()),409,"other buyer cannot purchase reserved item");
        status(create(buyer,p2,request),409,"request key cannot change product");check(productState(p2).equals("ON_SALE"),"key conflict leaves other product on sale");
        status(call("GET","/orders/"+id,outsider,null),403,"outsider cannot read order");status(call("GET","/orders/"+id,null,null),401,"order detail requires login");
        check(call("GET","/orders/"+id,seller,null).data().get("viewerRole").getAsString().equals("seller"),"seller can view own order");
        check(call("GET","/orders?role=buyer&buyerId=999",buyer,null).data().get("total").getAsLong()==1,"buyer list ignores spoofed identity");
        check(call("GET","/orders?role=seller",seller,null).data().get("total").getAsLong()==1,"seller list has real order");
        check(call("GET","/orders?role=buyer",outsider,null).data().get("total").getAsLong()==0,"outsider list isolated");
        for(String action:List.of("confirm","simulate-pay","deliver","receive","cancel"))status(act(outsider,id,action),403,"outsider action denied "+action);
        for(String action:List.of("confirm","deliver"))status(act(buyer,id,action),403,"buyer cannot act as seller");
        for(String action:List.of("simulate-pay","receive"))status(act(seller,id,action),403,"seller cannot act as buyer");
        status(act(buyer,id,"simulate-pay"),409,"pay before seller confirmation denied");status(act(seller,id,"deliver"),409,"deliver unpaid denied");status(act(buyer,id,"receive"),409,"receive before delivery denied");
        state(act(seller,id,"confirm"),"PENDING_PAYMENT","RESERVED","UNPAID");status(act(seller,id,"confirm"),200,"seller confirm idempotent");
        state(act(buyer,id,"simulate-pay"),"PAID","RESERVED","SUCCEEDED");
        state(act(seller,id,"cancel"),"CANCELLED","ON_SALE","REFUNDED");
        check(count("SELECT COUNT(*) FROM simulated_payments WHERE order_id="+id+" AND amount=18.80 AND status='REFUNDED' AND refunded_at IS NOT NULL")==1,"simulated refund persisted atomically");
        status(act(buyer,id,"cancel"),200,"repeat cancellation idempotent");state(act(buyer,id,"simulate-pay"),"CANCELLED","ON_SALE","REFUNDED");
        check(count("SELECT COUNT(*) FROM order_events WHERE order_id="+id+" AND action='PAY'")==1,"no double payment event after refund");
        Map<String,Object> changed=draft();changed.put("title","阶段六修改后的虚构商品");changed.put("price","99.99");status(call("PUT","/products/"+p,seller,changed),200,"cancelled item editable again");
        Reply old=call("GET","/orders/"+id,buyer,null);check(old.data().get("amount").getAsString().equals("18.80")&&old.data().get("title").getAsString().equals("阶段六虚构交易验收商品"),"order snapshots survive later product edits");
        check(count("SELECT COUNT(*) FROM order_events WHERE order_id="+id+" AND action='CANCEL'")==1,"one cancellation event");
    }
    private static void completeFlow()throws Exception {
        long p=product(),id=create(other,p,key()).id();
        act(seller,id,"confirm");
        List<Request> pays=new ArrayList<>();for(int i=0;i<8;i++)pays.add(action(other,id,"simulate-pay"));
        for(Reply r:parallel(pays))status(r,200,"concurrent repeat pay succeeds once idempotently");
        check(count("SELECT COUNT(*) FROM simulated_payments WHERE order_id="+id)==1,"concurrent payment single row");check(count("SELECT COUNT(*) FROM order_events WHERE order_id="+id+" AND action='PAY'")==1,"concurrent payment single event");
        state(act(seller,id,"deliver"),"DELIVERED","RESERVED","SUCCEEDED");status(act(seller,id,"deliver"),200,"repeat delivery idempotent");
        status(act(other,id,"cancel"),409,"buyer cannot cancel after handover");status(act(seller,id,"cancel"),409,"seller cannot cancel after handover");
        List<Request> receipts=new ArrayList<>();for(int i=0;i<8;i++)receipts.add(action(other,id,"receive"));for(Reply r:parallel(receipts))status(r,200,"concurrent receipt idempotent");
        state(call("GET","/orders/"+id,other,null),"COMPLETED","SOLD","SUCCEEDED");
        check(count("SELECT COUNT(*) FROM order_events WHERE order_id="+id)==5,"complete chain has five unique events");
        check(call("GET","/orders/"+id,other,null).data().getAsJsonArray("allowedActions").isEmpty(),"completed order has no actions");
        state(act(other,id,"simulate-pay"),"COMPLETED","SOLD","SUCCEEDED");status(create(buyer,p,key()),409,"sold product cannot buy again");
        status(call("PUT","/products/"+p,seller,draft()),409,"sold product cannot edit");status(call("PUT","/products/"+p+"/status",seller,Map.of("status","ON_SALE")),409,"sold product cannot relist");
        status(call("DELETE","/products/"+p,seller,null),409,"sold product cannot delete");
    }
    private static void unpaidCancel()throws Exception {
        long p=product();String request=key();long id=create(buyer,p,request).id();state(act(buyer,id,"cancel"),"CANCELLED","ON_SALE","UNPAID");
        check(count("SELECT COUNT(*) FROM simulated_payments WHERE order_id="+id)==0,"unpaid cancellation creates no payment");
        Reply replay=create(buyer,p,request);check(replay.id()==id&&replay.data().get("status").getAsString().equals("CANCELLED"),"replay after cancellation never reorders");
        long next=create(other,p,key()).id();check(next!=id,"cancelled item can have new buyer order");
        state(act(buyer,id,"cancel"),"CANCELLED","RESERVED","UNPAID");check(productState(p).equals("RESERVED"),"replaying old cancellation never releases new buyer reservation");
        state(act(seller,next,"cancel"),"CANCELLED","ON_SALE","UNPAID");
    }
    private static void concurrentBuyers()throws Exception {
        long p=product();List<Request> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(new Request("POST","/orders",i%2==0?buyer:other,createBody(p,key())));
        List<Reply> responses=parallel(requests);check(responses.stream().filter(r->r.status==201).count()==1,"eight concurrent buyers exactly one winner");
        check(responses.stream().filter(r->r.status==409).count()==7,"seven competing buys rejected with conflict");
        check(count("SELECT COUNT(*) FROM orders WHERE product_id="+p+" AND status<>'CANCELLED'")==1&&productState(p).equals("RESERVED"),"single active order and reserved product after race");
        act(seller,responses.stream().filter(r->r.status==201).findFirst().orElseThrow().id(),"cancel");
    }
    private static void sameRequestRace()throws Exception {
        long p=product();String request=key();List<Request> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(new Request("POST","/orders",buyer,createBody(p,request)));
        List<Reply> responses=parallel(requests);long id=responses.get(0).id();for(Reply r:responses)check((r.status==200||r.status==201)&&r.id()==id,"concurrent same key returns same order");
        check(responses.stream().filter(r->r.status==201).count()==1,"same key has one creation");
        check(count("SELECT COUNT(*) FROM orders WHERE product_id="+p)==1,"same key single persisted order");act(seller,id,"cancel");
    }
    private static void crossProductKeyRace()throws Exception {
        long p=product(),q=product();String request=key();List<Reply> responses=parallel(List.of(new Request("POST","/orders",buyer,createBody(p,request)),new Request("POST","/orders",buyer,createBody(q,request))));
        check(responses.stream().filter(r->r.status==201).count()==1&&responses.stream().filter(r->r.status==409).count()==1,"same request racing different products buys one only");
        Reply winner=responses.stream().filter(r->r.status==201).findFirst().orElseThrow();long won=winner.data().get("productId").getAsLong();check(productState(won==p?q:p).equals("ON_SALE"),"failed cross-product transaction releases no stock");act(seller,winner.id(),"cancel");
    }
    private static void paymentCancelRace()throws Exception {
        long p=product(),id=create(buyer,p,key()).id();act(seller,id,"confirm");List<Request> requests=new ArrayList<>();for(int i=0;i<8;i++)requests.add(action(i%2==0?buyer:seller,id,i%2==0?"simulate-pay":"cancel"));
        for(Reply r:parallel(requests))check(r.status==200||r.status==409,"pay cancel race has valid response");
        Reply result=call("GET","/orders/"+id,buyer,null);check(result.data().get("status").getAsString().equals("CANCELLED")&&productState(p).equals("ON_SALE"),"pay cancel race ends cancelled and sale restored");
        check(count("SELECT COUNT(*) FROM simulated_payments WHERE order_id="+id+" AND status='SUCCEEDED'")==0,"pay cancel race never leaves unrefunded payment");
        check(count("SELECT COUNT(*) FROM simulated_payments WHERE order_id="+id)<=1,"pay cancel race at most one payment");
    }
    private static void deliveryCancelRace()throws Exception {
        long p=product(),id=create(buyer,p,key()).id();act(seller,id,"confirm");act(buyer,id,"simulate-pay");
        List<Reply> responses=parallel(List.of(action(seller,id,"deliver"),action(buyer,id,"cancel")));check(responses.stream().filter(r->r.status==200).count()==1&&responses.stream().filter(r->r.status==409).count()==1,"delivery cancel race exactly one legal transition");
        Reply value=call("GET","/orders/"+id,buyer,null);if(value.data().get("status").getAsString().equals("DELIVERED")){state(value,"DELIVERED","RESERVED","SUCCEEDED");act(buyer,id,"receive");}else state(value,"CANCELLED","ON_SALE","REFUNDED");
    }
    private static void saleStateRace()throws Exception {
        long p=product();List<Reply> replies=parallel(List.of(new Request("POST","/orders",buyer,createBody(p,key())),new Request("PUT","/products/"+p+"/status",seller,Map.of("status","OFF_SHELF"))));
        if(replies.get(0).status==201){status(replies.get(1),409,"purchase locks out off shelf race");check(productState(p).equals("RESERVED"),"purchase winner reserves product");act(seller,replies.get(0).id(),"cancel");}else{status(replies.get(0),409,"off shelf winner prevents order");status(replies.get(1),200,"off shelf race winner");check(productState(p).equals("OFF_SHELF")&&count("SELECT COUNT(*) FROM orders WHERE product_id="+p)==0,"no partial order after off shelf race");}
    }
    private static void assertInvariants()throws SQLException {
        check(count("SELECT COUNT(*) FROM orders o JOIN products p ON p.id=o.product_id WHERE o.status<>'CANCELLED' AND ((o.status='COMPLETED' AND p.status<>'SOLD') OR (o.status<>'COMPLETED' AND p.status<>'RESERVED'))")==0,"global active order product state invariant");
        check(count("SELECT COUNT(*) FROM (SELECT product_id FROM orders WHERE status<>'CANCELLED' GROUP BY product_id HAVING COUNT(*)>1) x")==0,"global no duplicate effective purchase");
        check(count("SELECT COUNT(*) FROM orders o LEFT JOIN simulated_payments m ON m.order_id=o.id WHERE (o.status IN ('PAID','DELIVERED','COMPLETED') AND (m.status IS NULL OR m.status<>'SUCCEEDED' OR m.amount<>o.amount)) OR (o.status='CANCELLED' AND m.status='SUCCEEDED') OR (o.status IN ('PENDING_CONFIRMATION','PENDING_PAYMENT') AND m.order_id IS NOT NULL)")==0,"global order payment amount status invariant");
        check(count("SELECT COUNT(*) FROM orders o WHERE o.status<>(SELECT e.to_status FROM order_events e WHERE e.order_id=o.id ORDER BY e.id DESC LIMIT 1)")==0,"global order matches last event");
    }
    private static void cleanup()throws Exception {
        for(long p:PRODUCTS){
            try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("SELECT id,buyer_id,status FROM orders WHERE product_id=? AND status NOT IN ('CANCELLED','COMPLETED')")){s.setLong(1,p);try(ResultSet r=s.executeQuery()){if(r.next()){if(r.getString(3).equals("DELIVERED"))act(BUYERS.get(r.getLong(2)),r.getLong(1),"receive");else act(seller,r.getLong(1),"cancel");}}}
            if(Set.of("ON_SALE","OFF_SHELF").contains(productState(p)))status(call("DELETE","/products/"+p,seller,null),200,"only fixture unsold product soft deleted");
        }
    }
    private static Map<String,Object> draft(){Map<String,Object> d=new LinkedHashMap<>();d.put("title","阶段六虚构交易验收商品");d.put("description","阶段六真实HTTP/JDBC并发验收的虚构商品记录");d.put("price","18.80");d.put("categoryId",category);d.put("conditionLabel","九成新");d.put("tradeLocation","虚构测试大学图书馆");d.put("imageIds",List.of());return d;}
    private static long product()throws Exception {Reply r=call("POST","/products",seller,draft());status(r,201,"fixture product published");long id=r.id();PRODUCTS.add(id);return id;}
    private static String account(String username)throws Exception {String password=UUID.randomUUID()+"A9";Reply registered=call("POST","/auth/register",null,Map.of("username",username,"password",password,"confirmPassword",password,"nickname","阶段六虚构交易验收用户"));status(registered,201,"fixture account registered");String token=call("POST","/auth/login",null,Map.of("username",username,"password",password)).data().get("token").getAsString();BUYERS.put(registered.id(),token);return token;}
    private static long me(String token)throws Exception{return call("GET","/users/me",token,null).id();}
    private static Reply create(String token,long product,String request)throws Exception{return call("POST","/orders",token,createBody(product,request));}
    private static Map<String,Object> createBody(long product,String request){return Map.of("productId",product,"requestId",request);}
    private static Reply act(String token,long id,String action)throws Exception{return call("POST","/orders/"+id+"/"+action,token,null);}
    private static Request action(String token,long id,String action){return new Request("POST","/orders/"+id+"/"+action,token,null);}
    private static String key(){return UUID.randomUUID().toString();}
    private static Reply call(String method,String path,String token,Object body)throws Exception {HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");if(token!=null)r.header("Authorization","Bearer "+token);HttpResponse<String> response=HTTP.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),JsonParser.parseString(response.body()).getAsJsonObject());}
    private static List<Reply> parallel(List<Request> requests)throws Exception {ExecutorService executor=Executors.newFixedThreadPool(requests.size());CountDownLatch start=new CountDownLatch(1);try{List<Future<Reply>> futures=new ArrayList<>();for(Request r:requests)futures.add(executor.submit(()->{start.await();return call(r.method,r.path,r.token,r.body);}));start.countDown();List<Reply> replies=new ArrayList<>();for(Future<Reply> future:futures)replies.add(future.get(25,TimeUnit.SECONDS));return replies;}finally{executor.shutdownNow();}}
    private static long count(String sql)throws SQLException {try(Connection c=Database.open();PreparedStatement s=c.prepareStatement(sql);ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}
    private static String productState(long p)throws SQLException {try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("SELECT status FROM products WHERE id=?")){s.setLong(1,p);try(ResultSet r=s.executeQuery()){r.next();return r.getString(1);}}}
    private static void state(Reply r,String order,String product,String payment){check(r.status==200||r.status==201,"order transition succeeded [HTTP "+r.status+"]");check(r.data().get("status").getAsString().equals(order)&&r.data().get("productStatus").getAsString().equals(product)&&r.data().get("paymentStatus").getAsString().equals(payment),"coherent state "+order+" / "+product+" / "+payment);}
    private static void status(Reply r,int expected,String label){check(r.status==expected,label+" [HTTP "+r.status+"]");}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);CHECKS.add(label);}
}
