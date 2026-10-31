package com.campusmarket.dao;

import com.campusmarket.Database;
import com.campusmarket.auth.ApiException;
import java.sql.*;
import java.util.*;

/** Single-item trading: every writer locks product first, then order, and commits both together. */
public final class OrderDao {
    private static final String SELECT = "SELECT o.*,b.nickname AS buyer_nickname,s.nickname AS seller_nickname,"+
            "p.status AS product_status,m.status AS payment_status,m.paid_at,m.refunded_at FROM orders o "+
            "JOIN users b ON b.id=o.buyer_id JOIN users s ON s.id=o.seller_id JOIN products p ON p.id=o.product_id "+
            "LEFT JOIN simulated_payments m ON m.order_id=o.id ";

    public Map<String,Object> create(long buyer,long product,String requestId)throws SQLException {
        try(Connection c=writeConnection()) {
            try {
                String title,description,condition,location,status;long seller,category;java.math.BigDecimal amount;
                try(PreparedStatement s=c.prepareStatement("SELECT seller_id,category_id,title,description,price,condition_label,trade_location,status FROM products WHERE id=? FOR UPDATE")){
                    s.setLong(1,product);try(ResultSet r=s.executeQuery()){
                        if(!r.next())throw missing();seller=r.getLong(1);category=r.getLong(2);title=r.getString(3);description=r.getString(4);
                        amount=r.getBigDecimal(5);condition=r.getString(6);location=r.getString(7);status=r.getString(8);
                    }
                }
                Long existing=findRequest(c,buyer,requestId);
                if(existing!=null){Map<String,Object> value=detail(c,buyer,existing);sameProduct(value,product);value.put("replayed",true);c.commit();return value;}
                if(seller==buyer)throw new ApiException(403,40301,"不能购买自己发布的商品");
                if(!status.equals("ON_SALE"))throw conflict("商品已被下单、下架或售出");
                try(PreparedStatement s=c.prepareStatement("SELECT c.id FROM categories c JOIN users u ON u.id=? WHERE c.id=? AND c.enabled=1 AND u.status='ACTIVE' FOR SHARE")){
                    s.setLong(1,seller);s.setLong(2,category);try(ResultSet r=s.executeQuery()){if(!r.next())throw conflict("商品当前不可购买");}
                }
                long id;
                try(PreparedStatement s=c.prepareStatement("INSERT INTO orders(product_id,buyer_id,seller_id,request_id,product_title,product_description,condition_label,trade_location,amount) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    s.setLong(1,product);s.setLong(2,buyer);s.setLong(3,seller);s.setString(4,requestId);s.setString(5,title);s.setString(6,description);s.setString(7,condition);s.setString(8,location);s.setBigDecimal(9,amount);s.executeUpdate();
                    try(ResultSet r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}
                }
                setProduct(c,product,"RESERVED");event(c,id,buyer,"CREATE",null,"PENDING_CONFIRMATION");
                Map<String,Object> value=detail(c,buyer,id);value.put("replayed",false);c.commit();return value;
            } catch(SQLException e){
                c.rollback();
                // A request key racing on different products must not buy both products.
                if(e.getErrorCode()==1062){try(Connection retry=Database.open()){
                    Long existing=findRequest(retry,buyer,requestId);
                    if(existing!=null){Map<String,Object> value=detail(retry,buyer,existing);sameProduct(value,product);value.put("replayed",true);return value;}
                    throw conflict("商品已有有效订单，请刷新后查看");
                }}throw e;
            } catch(RuntimeException e){c.rollback();throw e;}
        }
    }

    public Map<String,Object> act(long user,long id,String action)throws SQLException {
        try(Connection c=writeConnection()){
            try {
                long product,buyer,seller;
                try(PreparedStatement s=c.prepareStatement("SELECT product_id,buyer_id,seller_id FROM orders WHERE id=?")){
                    s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw missing();product=r.getLong(1);buyer=r.getLong(2);seller=r.getLong(3);}
                }
                authorize(user,buyer,seller,action);
                String productState;
                try(PreparedStatement s=c.prepareStatement("SELECT status FROM products WHERE id=? FOR UPDATE")){s.setLong(1,product);try(ResultSet r=s.executeQuery()){r.next();productState=r.getString(1);}}
                String state;java.math.BigDecimal amount;
                try(PreparedStatement s=c.prepareStatement("SELECT status,amount FROM orders WHERE id=? FOR UPDATE")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){r.next();state=r.getString(1);amount=r.getBigDecimal(2);}}
                try(PreparedStatement s=c.prepareStatement("SELECT id FROM order_events WHERE order_id=? AND action=?")){
                    s.setLong(1,id);s.setString(2,action);try(ResultSet r=s.executeQuery()){if(r.next()){Map<String,Object> value=detail(c,user,id);c.commit();return value;}}
                }
                String next=switch(action){
                    case "CONFIRM" -> {require(state,"PENDING_CONFIRMATION");yield "PENDING_PAYMENT";}
                    case "PAY" -> {require(state,"PENDING_PAYMENT");yield "PAID";}
                    case "DELIVER" -> {require(state,"PAID");yield "DELIVERED";}
                    case "RECEIVE" -> {require(state,"DELIVERED");yield "COMPLETED";}
                    case "CANCEL" -> {if(!Set.of("PENDING_CONFIRMATION","PENDING_PAYMENT","PAID").contains(state))throw conflict("已交付或结束的订单不能取消");yield "CANCELLED";}
                    default -> throw new ApiException(400,40001,"不支持的订单操作");
                };
                if(!productState.equals("RESERVED"))throw conflict("商品与订单状态不一致，请联系管理员核查");
                if(action.equals("PAY")){
                    try(PreparedStatement s=c.prepareStatement("INSERT INTO simulated_payments(order_id,amount,status) VALUES(?,?,'SUCCEEDED')")){s.setLong(1,id);s.setBigDecimal(2,amount);s.executeUpdate();}
                }
                if(next.equals("CANCELLED")){
                    if(state.equals("PAID"))try(PreparedStatement s=c.prepareStatement("UPDATE simulated_payments SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP(3) WHERE order_id=? AND status='SUCCEEDED'")){s.setLong(1,id);if(s.executeUpdate()!=1)throw conflict("模拟支付记录不一致");}
                    setProduct(c,product,"ON_SALE");
                }else if(next.equals("COMPLETED"))setProduct(c,product,"SOLD");
                try(PreparedStatement s=c.prepareStatement("UPDATE orders SET status=? WHERE id=?")){s.setString(1,next);s.setLong(2,id);s.executeUpdate();}
                event(c,id,user,action,state,next);Map<String,Object> value=detail(c,user,id);c.commit();return value;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }

    public Map<String,Object> detail(long user,long id)throws SQLException {
        try(Connection c=Database.open()){c.setReadOnly(true);c.setAutoCommit(false);try{Map<String,Object> value=detail(c,user,id);c.commit();return value;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    private static Map<String,Object> detail(Connection c,long user,long id)throws SQLException {
        Map<String,Object> value;
        try(PreparedStatement s=c.prepareStatement(SELECT+"WHERE o.id=?")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){
            if(!r.next())throw missing();authorize(user,r.getLong("buyer_id"),r.getLong("seller_id"),"VIEW");value=row(r,user);
        }}
        List<Map<String,Object>> events=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT action,from_status,to_status,created_at,actor_id FROM order_events WHERE order_id=? ORDER BY id")){
            s.setLong(1,id);try(ResultSet r=s.executeQuery()){while(r.next()){Map<String,Object> e=new LinkedHashMap<>();e.put("action",r.getString(1));e.put("fromStatus",r.getString(2));e.put("toStatus",r.getString(3));e.put("createdAt",r.getString(4));e.put("actorId",r.getLong(5));events.add(e);}}
        }value.put("events",events);return value;
    }
    public Map<String,Object> list(long user,String role,int page,int size)throws SQLException {
        String filter=switch(role){case "buyer"->"o.buyer_id=?";case "seller"->"o.seller_id=?";default->throw new ApiException(400,40001,"请选择买家或卖家订单");};
        try(Connection c=Database.open()){
            c.setReadOnly(true);c.setAutoCommit(false);
            try{
                long total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM orders o WHERE "+filter)){s.setLong(1,user);try(ResultSet r=s.executeQuery()){r.next();total=r.getLong(1);}}
                List<Map<String,Object>> items=new ArrayList<>();
                try(PreparedStatement s=c.prepareStatement(SELECT+"WHERE "+filter+" ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?")){
                    s.setLong(1,user);s.setInt(2,size);s.setInt(3,(page-1)*size);try(ResultSet r=s.executeQuery()){while(r.next())items.add(row(r,user));}
                }c.commit();return Map.of("items",items,"total",total,"page",page,"pageSize",size);
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private static Map<String,Object> row(ResultSet r,long user)throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>();
        for(String[] pair:new String[][]{{"id","id"},{"productId","product_id"},{"buyerId","buyer_id"},{"sellerId","seller_id"}})value.put(pair[0],r.getLong(pair[1]));
        for(String[] pair:new String[][]{{"requestId","request_id"},{"title","product_title"},{"description","product_description"},{"conditionLabel","condition_label"},{"tradeLocation","trade_location"},{"status","status"},{"productStatus","product_status"},{"buyerNickname","buyer_nickname"},{"sellerNickname","seller_nickname"},{"createdAt","created_at"},{"updatedAt","updated_at"},{"paidAt","paid_at"},{"refundedAt","refunded_at"}})value.put(pair[0],r.getString(pair[1]));
        value.put("amount",r.getBigDecimal("amount").toPlainString());String payment=r.getString("payment_status");value.put("paymentStatus",payment==null?"UNPAID":payment);
        value.put("paymentMode","SIMULATED");boolean buyer=user==r.getLong("buyer_id");value.put("viewerRole",buyer?"buyer":"seller");
        String state=r.getString("status");List<String> actions=new ArrayList<>();
        if(!buyer&&state.equals("PENDING_CONFIRMATION"))actions.add("CONFIRM");
        if(buyer&&state.equals("PENDING_PAYMENT"))actions.add("PAY");
        if(!buyer&&state.equals("PAID"))actions.add("DELIVER");
        if(buyer&&state.equals("DELIVERED"))actions.add("RECEIVE");
        if(Set.of("PENDING_CONFIRMATION","PENDING_PAYMENT","PAID").contains(state))actions.add("CANCEL");value.put("allowedActions",actions);return value;
    }
    private static void authorize(long user,long buyer,long seller,String action){
        if(user!=buyer&&user!=seller)throw new ApiException(403,40301,"只能查看或操作自己的订单");
        if((Set.of("PAY","RECEIVE").contains(action)&&user!=buyer)||(Set.of("CONFIRM","DELIVER").contains(action)&&user!=seller))throw new ApiException(403,40301,"当前身份无权执行该操作");
    }
    private static Connection writeConnection()throws SQLException {Connection c=Database.open();try{c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);return c;}catch(SQLException e){c.close();throw e;}}
    private static Long findRequest(Connection c,long buyer,String key)throws SQLException {try(PreparedStatement s=c.prepareStatement("SELECT id FROM orders WHERE buyer_id=? AND request_id=?")){s.setLong(1,buyer);s.setString(2,key);try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):null;}}}
    private static void sameProduct(Map<String,Object> value,long product){if(((Number)value.get("productId")).longValue()!=product)throw conflict("该下单请求编号已用于其他商品");}
    private static void require(String actual,String expected){if(!actual.equals(expected))throw conflict("订单状态已变化，请刷新后重试");}
    private static void setProduct(Connection c,long product,String state)throws SQLException {try(PreparedStatement s=c.prepareStatement("UPDATE products SET status=? WHERE id=?")){s.setString(1,state);s.setLong(2,product);s.executeUpdate();}}
    private static void event(Connection c,long id,long user,String action,String from,String to)throws SQLException {try(PreparedStatement s=c.prepareStatement("INSERT INTO order_events(order_id,actor_id,action,from_status,to_status) VALUES(?,?,?,?,?)")){s.setLong(1,id);s.setLong(2,user);s.setString(3,action);s.setString(4,from);s.setString(5,to);s.executeUpdate();}}
    private static ApiException conflict(String message){return new ApiException(409,40901,message);}
    private static ApiException missing(){return new ApiException(404,40401,"订单或商品不存在");}
}
