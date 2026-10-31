package com.campusmarket.network;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Actual Java Android transport/Gson, local Tomcat and MySQL; no device and no mocks. */
public final class OrderApiIntegrationTest {
    private static final String BASE="http://127.0.0.1:8080/campus-market/api/v1";
    @Test public void completeTransactionAndReplay()throws Exception {
        try(Fixture f=new Fixture()){
            String key=UUID.randomUUID().toString();OrderApi.Order created=f.orders.create(f.buyer,f.product,key);f.order=created.id;
            assertEquals("PENDING_CONFIRMATION",created.status);assertEquals("RESERVED",created.productStatus);assertEquals("16.50",created.amount);assertEquals("SIMULATED",created.paymentMode);
            assertEquals(created.id,f.orders.create(f.buyer,f.product,key).id);assertTrue(f.orders.create(f.buyer,f.product,key).replayed);
            assertEquals(created.id,f.orders.list(f.buyer,"buyer",1).items[0].id);assertEquals(created.id,f.orders.list(f.seller,"seller",1).items[0].id);
            assertTrue(Arrays.asList(f.orders.detail(f.seller,created.id).allowedActions).contains("CONFIRM"));
            try{f.orders.act(f.buyer,created.id,"CONFIRM");fail("Buyer cannot confirm as seller");}catch(UserApi.Failure failure){assertEquals(403,failure.status);}
            try{f.orders.act(f.buyer,created.id,"PAY");fail("Cannot pay out of order");}catch(UserApi.Failure failure){assertEquals(409,failure.status);}
            assertEquals("PENDING_PAYMENT",f.orders.act(f.seller,created.id,"CONFIRM").status);
            assertEquals("SUCCEEDED",f.orders.act(f.buyer,created.id,"PAY").paymentStatus);assertEquals("PAID",f.orders.act(f.buyer,created.id,"PAY").status);
            assertEquals("DELIVERED",f.orders.act(f.seller,created.id,"DELIVER").status);
            try{f.orders.act(f.buyer,created.id,"CANCEL");fail("No cancellation after physical handover");}catch(UserApi.Failure failure){assertEquals(409,failure.status);}
            OrderApi.Order complete=f.orders.act(f.buyer,created.id,"RECEIVE");assertEquals("COMPLETED",complete.status);assertEquals("SOLD",complete.productStatus);assertEquals(0,complete.allowedActions.length);assertEquals(5,complete.events.length);
            assertEquals("CREATE",complete.events[0].action);assertEquals("RECEIVE",complete.events[4].action);
            assertEquals("COMPLETED",f.orders.act(f.buyer,created.id,"RECEIVE").status);
        }
    }
    @Test public void simulatedRefundAndStableSnapshot()throws Exception {
        try(Fixture f=new Fixture()){
            OrderApi.Order made=f.orders.create(f.buyer,f.product,UUID.randomUUID().toString());f.order=made.id;
            f.orders.act(f.seller,f.order,"CONFIRM");f.orders.act(f.buyer,f.order,"PAY");
            OrderApi.Order cancelled=f.orders.act(f.seller,f.order,"CANCEL");assertEquals("CANCELLED",cancelled.status);assertEquals("REFUNDED",cancelled.paymentStatus);assertEquals("ON_SALE",cancelled.productStatus);assertNotNull(cancelled.refundedAt);
            assertEquals("CANCELLED",f.orders.act(f.buyer,f.order,"PAY").status);
            Map<String,Object> updated=f.draft();updated.put("price","88.88");f.catalog.save(f.seller,f.product,updated);
            assertEquals("16.50",f.orders.detail(f.buyer,f.order).amount);assertEquals("88.88",f.catalog.detail(f.product).price);
        }
    }
    @Test public void protectedEndpointsRejectMissingSession()throws Exception {
        try{new OrderApi(BASE).list(null,"buyer",1);fail("Orders require a session");}catch(UserApi.Failure failure){assertEquals(401,failure.status);}
    }
    private static final class Fixture implements AutoCloseable {
        final UserApi users=new UserApi(BASE);final CatalogApi catalog=new CatalogApi(BASE);final OrderApi orders=new OrderApi(BASE);
        final String seller,buyer;final long product;long order;
        Fixture()throws Exception {String name="p6_client_"+UUID.randomUUID().toString().replace("-","").substring(0,10);seller=account(name+"s");buyer=account(name+"b");product=catalog.save(seller,0,draft()).id;}
        private String account(String name)throws Exception {String password=UUID.randomUUID()+"B7";users.register(name,password,password,"阶段六客户端虚构验收用户");return users.login(name,password).token;}
        Map<String,Object> draft()throws Exception {Map<String,Object> d=new LinkedHashMap<>();d.put("title","阶段六客户端虚构交易商品");d.put("description","真实客户端传输与Gson集成验收的虚构记录");d.put("price","16.50");d.put("categoryId",catalog.categories()[0].id);d.put("conditionLabel","九成新");d.put("tradeLocation","虚构测试校区");d.put("imageIds",Collections.emptyList());return d;}
        public void close()throws Exception {
            if(order>0){OrderApi.Order current=orders.detail(buyer,order);if(current.status.equals("DELIVERED"))orders.act(buyer,order,"RECEIVE");else if(!current.status.equals("COMPLETED")&&!current.status.equals("CANCELLED"))orders.act(seller,order,"CANCEL");}
            CatalogApi.Product current=catalog.own(seller,product);if("ON_SALE".equals(current.status)||"OFF_SHELF".equals(current.status))catalog.delete(seller,product);
            users.logout(buyer);users.logout(seller);
        }
    }
}
