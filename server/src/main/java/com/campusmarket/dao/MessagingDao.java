package com.campusmarket.dao;

import com.campusmarket.Database;
import com.campusmarket.auth.ApiException;
import java.sql.*;
import java.util.*;

/** A conversation lock serializes message sequence allocation, replay checks and read markers. */
public final class MessagingDao {
    private static final String SELECT="SELECT c.*,p.status AS product_status,b.nickname AS buyer_nickname,s.nickname AS seller_nickname,"+
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id=c.id AND m.sender_id<>? AND m.seq>CASE WHEN c.buyer_id=? THEN c.buyer_read_seq ELSE c.seller_read_seq END) AS unread_count "+
            "FROM conversations c JOIN products p ON p.id=c.product_id JOIN users b ON b.id=c.buyer_id JOIN users s ON s.id=c.seller_id ";
    public Map<String,Object> open(long buyer,long product)throws SQLException {
        try(Connection c=writer()) {
            try{
                long seller,category;String title,state;
                // Match the product-first lock order used by publishing and orders.
                try(PreparedStatement s=c.prepareStatement("SELECT seller_id,category_id,title,status FROM products WHERE id=? FOR SHARE")){
                    s.setLong(1,product);try(ResultSet r=s.executeQuery()){if(!r.next())throw missing();seller=r.getLong(1);category=r.getLong(2);title=r.getString(3);state=r.getString(4);}
                }
                if(buyer==seller)throw new ApiException(403,40301,"不能向自己发起商品咨询");
                Long existing=find(c,buyer,product);
                if(existing!=null){Map<String,Object> value=one(c,buyer,existing);c.commit();return value;}
                if(!state.equals("ON_SALE"))throw new ApiException(409,40901,"商品已不可售，无法新建咨询；已有会话仍可使用");
                try(PreparedStatement s=c.prepareStatement("SELECT c.id FROM categories c JOIN users u ON u.id=? WHERE c.id=? AND c.enabled=1 AND u.status='ACTIVE' FOR SHARE")){
                    s.setLong(1,seller);s.setLong(2,category);try(ResultSet r=s.executeQuery()){if(!r.next())throw new ApiException(409,40901,"商品当前不可咨询");}
                }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO conversations(product_id,buyer_id,seller_id,product_title) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")){
                    s.setLong(1,product);s.setLong(2,buyer);s.setLong(3,seller);s.setString(4,title);s.executeUpdate();
                }
                long id=Objects.requireNonNull(find(c,buyer,product));Map<String,Object> value=one(c,buyer,id);c.commit();return value;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public Map<String,Object> send(long user,long id,String request,String content)throws SQLException {
        try(Connection c=writer()){
            try{
                long buyer,seller,last;
                try(PreparedStatement s=c.prepareStatement("SELECT buyer_id,seller_id,last_seq FROM conversations WHERE id=? FOR UPDATE")){
                    s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw missing();buyer=r.getLong(1);seller=r.getLong(2);last=r.getLong(3);}
                }
                authorize(user,buyer,seller);
                try(PreparedStatement s=c.prepareStatement("SELECT * FROM messages WHERE conversation_id=? AND sender_id=? AND request_id=?")){
                    s.setLong(1,id);s.setLong(2,user);s.setString(3,request);try(ResultSet r=s.executeQuery()){
                        if(r.next()){if(!content.equals(r.getString("content")))throw new ApiException(409,40901,"同一发送请求不能更改消息内容");Map<String,Object> value=message(r,user);value.put("replayed",true);c.commit();return value;}
                    }
                }
                try(PreparedStatement s=c.prepareStatement("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR SHARE")){
                    s.setLong(1,user==buyer?seller:buyer);try(ResultSet r=s.executeQuery()){if(!r.next())throw new ApiException(403,40301,"对方账号当前不可接收新消息，历史仍可查看");}
                }
                long seq=last+1;
                try(PreparedStatement s=c.prepareStatement("INSERT INTO messages(conversation_id,seq,sender_id,request_id,content) VALUES(?,?,?,?,?)")){
                    s.setLong(1,id);s.setLong(2,seq);s.setLong(3,user);s.setString(4,request);s.setString(5,content);s.executeUpdate();
                }
                try(PreparedStatement s=c.prepareStatement("UPDATE conversations SET last_seq=?,last_message_preview=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?")){
                    s.setLong(1,seq);s.setString(2,content);s.setLong(3,id);s.executeUpdate();
                }
                Map<String,Object> value;
                try(PreparedStatement s=c.prepareStatement("SELECT * FROM messages WHERE conversation_id=? AND seq=?")){s.setLong(1,id);s.setLong(2,seq);try(ResultSet r=s.executeQuery()){r.next();value=message(r,user);}}
                value.put("replayed",false);c.commit();return value;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public Map<String,Object> read(long user,long id,long through)throws SQLException {
        try(Connection c=writer()){
            try{
                long buyer,seller,last;
                try(PreparedStatement s=c.prepareStatement("SELECT buyer_id,seller_id,last_seq FROM conversations WHERE id=? FOR UPDATE")){
                    s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw missing();buyer=r.getLong(1);seller=r.getLong(2);last=r.getLong(3);}
                }
                authorize(user,buyer,seller);if(through>last)throw new ApiException(400,40001,"已读位置超出消息范围");
                String column=user==buyer?"buyer_read_seq":"seller_read_seq";
                try(PreparedStatement s=c.prepareStatement("UPDATE conversations SET "+column+"=GREATEST("+column+",?) WHERE id=?")){s.setLong(1,through);s.setLong(2,id);s.executeUpdate();}
                Map<String,Object> value=one(c,user,id);c.commit();return value;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public Map<String,Object> messages(long user,long id,Long after,Long before,int limit)throws SQLException {
        try(Connection c=Database.open()){
            c.setReadOnly(true);c.setAutoCommit(false);
            try{
                Map<String,Object> conversation=one(c,user,id);long last=((Number)conversation.get("lastSeq")).longValue();
                if(after!=null&&after>last)throw new ApiException(400,40001,"增量位置超出消息范围");
                String filter=after!=null?" AND seq>?":before!=null?" AND seq<?":"";
                List<Map<String,Object>> items=new ArrayList<>();
                try(PreparedStatement s=c.prepareStatement("SELECT * FROM messages WHERE conversation_id=?"+filter+" ORDER BY seq "+(after!=null?"ASC":"DESC")+" LIMIT ?")){
                    int index=1;s.setLong(index++,id);if(after!=null||before!=null)s.setLong(index++,after!=null?after:before);s.setInt(index,limit);
                    try(ResultSet r=s.executeQuery()){while(r.next())items.add(message(r,user));}
                }
                if(after==null)Collections.reverse(items);
                long first=items.isEmpty()?0:((Number)items.get(0).get("seq")).longValue(),end=items.isEmpty()?(after==null?last:after):((Number)items.get(items.size()-1).get("seq")).longValue();
                c.commit();return Map.of("conversation",conversation,"items",items,"hasMoreBefore",first>1,"hasMoreAfter",end<last);
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public Map<String,Object> list(long user,int page,int size)throws SQLException {
        try(Connection c=Database.open()){
            c.setReadOnly(true);c.setAutoCommit(false);
            try{
                long total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM conversations WHERE buyer_id=? OR seller_id=?")){s.setLong(1,user);s.setLong(2,user);try(ResultSet r=s.executeQuery()){r.next();total=r.getLong(1);}}
                List<Map<String,Object>> items=new ArrayList<>();
                try(PreparedStatement s=c.prepareStatement(SELECT+"WHERE c.buyer_id=? OR c.seller_id=? ORDER BY c.updated_at DESC,c.id DESC LIMIT ? OFFSET ?")){
                    s.setLong(1,user);s.setLong(2,user);s.setLong(3,user);s.setLong(4,user);s.setInt(5,size);s.setInt(6,(page-1)*size);try(ResultSet r=s.executeQuery()){while(r.next())items.add(conversation(r,user));}
                }
                long unread=unread(c,user);c.commit();return Map.of("items",items,"page",page,"pageSize",size,"total",total,"unreadTotal",unread);
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public Map<String,Object> detail(long user,long id)throws SQLException {try(Connection c=Database.open()){return one(c,user,id);}}
    public long unread(long user)throws SQLException {try(Connection c=Database.open()){return unread(c,user);}}
    private static long unread(Connection c,long user)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE (c.buyer_id=? OR c.seller_id=?) AND m.sender_id<>? AND m.seq>CASE WHEN c.buyer_id=? THEN c.buyer_read_seq ELSE c.seller_read_seq END")){
            for(int i=1;i<=4;i++)s.setLong(i,user);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    private static Map<String,Object> one(Connection c,long user,long id)throws SQLException {
        try(PreparedStatement s=c.prepareStatement(SELECT+"WHERE c.id=?")){
            s.setLong(1,user);s.setLong(2,user);s.setLong(3,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw missing();authorize(user,r.getLong("buyer_id"),r.getLong("seller_id"));return conversation(r,user);}
        }
    }
    private static Map<String,Object> conversation(ResultSet r,long user)throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>();
        for(String[] p:new String[][]{{"id","id"},{"productId","product_id"},{"buyerId","buyer_id"},{"sellerId","seller_id"},{"lastSeq","last_seq"},{"unreadCount","unread_count"}})value.put(p[0],r.getLong(p[1]));
        for(String[] p:new String[][]{{"productTitle","product_title"},{"productStatus","product_status"},{"lastMessage","last_message_preview"},{"updatedAt","updated_at"},{"createdAt","created_at"}})value.put(p[0],r.getString(p[1]));
        boolean buyer=user==r.getLong("buyer_id");value.put("viewerRole",buyer?"buyer":"seller");value.put("peerNickname",r.getString(buyer?"seller_nickname":"buyer_nickname"));value.put("readSeq",r.getLong(buyer?"buyer_read_seq":"seller_read_seq"));return value;
    }
    private static Map<String,Object> message(ResultSet r,long user)throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>();for(String[] p:new String[][]{{"id","id"},{"conversationId","conversation_id"},{"seq","seq"},{"senderId","sender_id"}})value.put(p[0],r.getLong(p[1]));
        value.put("requestId",r.getString("request_id"));value.put("content",r.getString("content"));value.put("createdAt",r.getString("created_at"));value.put("mine",user==r.getLong("sender_id"));return value;
    }
    private static Long find(Connection c,long buyer,long product)throws SQLException {try(PreparedStatement s=c.prepareStatement("SELECT id FROM conversations WHERE buyer_id=? AND product_id=?")){s.setLong(1,buyer);s.setLong(2,product);try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):null;}}}
    private static void authorize(long user,long buyer,long seller){if(user!=buyer&&user!=seller)throw new ApiException(403,40301,"无权访问或发送该会话消息");}
    private static ApiException missing(){return new ApiException(404,40401,"商品或会话不存在");}
    private static Connection writer()throws SQLException {Connection c=Database.open();try{c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);return c;}catch(SQLException e){c.close();throw e;}}
}
