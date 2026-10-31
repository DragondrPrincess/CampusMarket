package com.campusmarket.network;

import java.io.IOException;
import java.util.*;

/** Synchronous methods are invoked only from an Activity's ExecutorService. */
public final class OrderApi {
    public static final class Event {public String action,fromStatus,toStatus,createdAt;public long actorId;}
    public static final class Order {
        public long id,productId,buyerId,sellerId;
        public String title,description,conditionLabel,tradeLocation,amount,status,productStatus;
        public String buyerNickname,sellerNickname,createdAt,updatedAt,paidAt,refundedAt,paymentStatus,paymentMode,viewerRole,requestId;
        public String[] allowedActions;public Event[] events;public boolean replayed;
    }
    public static final class Page {public Order[] items;public int page,pageSize;public long total;}
    private final UserApi transport;
    public OrderApi(){transport=new UserApi();}
    OrderApi(String base){transport=new UserApi(base);}
    public Order create(String token,long product,String requestId)throws IOException {
        Map<String,Object> body=new LinkedHashMap<>();body.put("productId",product);body.put("requestId",requestId);
        return transport.request("POST","/orders",token,body,Order.class);
    }
    public Order detail(String token,long id)throws IOException{return transport.request("GET","/orders/"+id,token,null,Order.class);}
    public Page list(String token,String role,int page)throws IOException {
        if(!role.equals("buyer")&&!role.equals("seller"))throw new IOException("Invalid role");
        Page result=transport.request("GET","/orders?role="+role+"&page="+page+"&pageSize=20",token,null,Page.class);
        if(result.items==null||result.page!=page||result.total<0)throw new IOException("Invalid order page");return result;
    }
    public Order act(String token,long id,String action)throws IOException {
        String path;switch(action){case "CONFIRM":path="confirm";break;case "PAY":path="simulate-pay";break;case "DELIVER":path="deliver";break;case "RECEIVE":path="receive";break;case "CANCEL":path="cancel";break;default:throw new IOException("Invalid action");}
        return transport.request("POST","/orders/"+id+"/"+path,token,null,Order.class);
    }
}
