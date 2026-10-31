package com.campusmarket.dao;

import com.campusmarket.Database;
import com.campusmarket.auth.ApiException;
import com.google.gson.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** All ownership checks and product/image changes share one transaction and row lock. */
public final class PublishingDao {
    public record Draft(String title,String description,BigDecimal price,long category,String condition,String location,List<Long> images) {}
    public static Draft validate(JsonObject body) {
        try {
            String title=text(body,"title",1,100),description=text(body,"description",1,2000);
            String price=text(body,"price",1,11);
            if(!price.matches("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?"))throw bad("价格格式不正确，最多两位小数");
            String category=text(body,"categoryId",1,18);if(!category.matches("[1-9][0-9]{0,17}"))throw bad("请选择有效分类");
            List<Long> images=new ArrayList<>();
            if(!body.has("imageIds")||!body.get("imageIds").isJsonArray())throw bad("图片编号须为数组");
            JsonArray array=body.getAsJsonArray("imageIds");
            if(array.size()>5)throw bad("最多上传五张图片");
            for(JsonElement value:array){if(!value.isJsonPrimitive())throw bad("图片编号不正确");String id=value.getAsString();if(!id.matches("[1-9][0-9]{0,17}"))throw bad("图片编号不正确");long n=Long.parseLong(id);if(images.contains(n))throw bad("图片不能重复");images.add(n);}
            return new Draft(title,description,new BigDecimal(price),Long.parseLong(category),text(body,"conditionLabel",1,30),text(body,"tradeLocation",1,120),images);
        }catch(IllegalStateException|UnsupportedOperationException|NumberFormatException e){throw bad("商品参数格式不正确");}
    }
    public static String text(JsonObject body,String key,int min,int max){
        if(!body.has(key)||!body.get(key).isJsonPrimitive()||!body.get(key).getAsJsonPrimitive().isString()&& !key.equals("categoryId"))throw bad("缺少或无效的字段："+key);
        String value=body.get(key).getAsString().strip();int count=value.codePointCount(0,value.length());
        if(count<min||count>max||value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw bad("字段长度或内容不正确："+key);
        return value;
    }
    public long save(long user,long id,Draft draft)throws SQLException {
        try(Connection c=Database.open()){
            c.setAutoCommit(false);
            try{
                if(id>0)lockOwner(c,id,user);
                try(PreparedStatement s=c.prepareStatement("SELECT id FROM categories WHERE id=? AND enabled=1 FOR SHARE")){s.setLong(1,draft.category);try(ResultSet r=s.executeQuery()){if(!r.next())throw bad("分类不存在或已停用");}}
                List<Long> sorted=new ArrayList<>(draft.images);Collections.sort(sorted);
                for(long image:sorted){try(PreparedStatement s=c.prepareStatement("SELECT uploader_id,product_id,active FROM product_images WHERE id=? FOR UPDATE")){s.setLong(1,image);try(ResultSet r=s.executeQuery()){
                    if(!r.next()||r.getLong(1)!=user||!r.getBoolean(3))throw new ApiException(403,40301,"无权使用该图片");
                    long attached=r.getLong(2);if(attached!=0&&attached!=id)throw bad("图片已用于其他商品");
                }}}
                if(id==0){try(PreparedStatement s=c.prepareStatement("INSERT INTO products(seller_id,category_id,title,description,price,condition_label,trade_location) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    s.setLong(1,user);bind(s,2,draft);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}
                }}else{try(PreparedStatement s=c.prepareStatement("UPDATE products SET category_id=?,title=?,description=?,price=?,condition_label=?,trade_location=? WHERE id=?")){bind(s,1,draft);s.setLong(7,id);s.executeUpdate();}}
                try(PreparedStatement s=c.prepareStatement("UPDATE product_images SET active=0 WHERE product_id=?")){s.setLong(1,id);s.executeUpdate();}
                for(int i=0;i<draft.images.size();i++){try(PreparedStatement s=c.prepareStatement("UPDATE product_images SET product_id=?,position=?,active=1 WHERE id=?")){s.setLong(1,id);s.setInt(2,i);s.setLong(3,draft.images.get(i));s.executeUpdate();}}
                c.commit();return id;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private static void bind(PreparedStatement s,int index,Draft d)throws SQLException{s.setLong(index++,d.category);s.setString(index++,d.title);s.setString(index++,d.description);s.setBigDecimal(index++,d.price);s.setString(index++,d.condition);s.setString(index,d.location);}
    private static void lockOwner(Connection c,long id,long user)throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT seller_id,status FROM products WHERE id=? FOR UPDATE")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){
            if(!r.next())throw new ApiException(404,40401,"商品不存在");
            if(r.getLong(1)!=user)throw new ApiException(403,40301,"只能管理自己的商品");
            if(!Set.of("ON_SALE","OFF_SHELF").contains(r.getString(2)))throw new ApiException(409,40901,"当前商品状态不允许修改");
        }}
    }
    public void status(long user,long id,String state)throws SQLException {
        if(!Set.of("ON_SALE","OFF_SHELF","DELETED").contains(state))throw bad("不支持的商品状态");
        try(Connection c=Database.open()){c.setAutoCommit(false);try{lockOwner(c,id,user);
            try(PreparedStatement s=c.prepareStatement("UPDATE products SET status=? WHERE id=?")){s.setString(1,state);s.setLong(2,id);s.executeUpdate();}c.commit();
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    public Map<String,Object> mine(long user,int page,int size,boolean favorites)throws SQLException {
        String filter=favorites?"p.id IN (SELECT product_id FROM favorites WHERE user_id=? AND active=1) AND p.status='ON_SALE' AND c.enabled=1 AND u.status='ACTIVE'":"p.seller_id=? AND p.status<>'DELETED'";
        return new CatalogDao().filtered(filter,user,page,size);
    }
    public Map<String,Object> ownDetail(long user,long id)throws SQLException {
        Map<String,Object> value=new CatalogDao().ownerDetail(user,id);return value;
    }
    public boolean favorite(long user,long id,Boolean active)throws SQLException {
        try(Connection c=Database.open()){c.setAutoCommit(false);try{
            if(active!=null&&active){try(PreparedStatement s=c.prepareStatement("SELECT p.id FROM products p JOIN categories c ON c.id=p.category_id JOIN users u ON u.id=p.seller_id WHERE p.id=? AND p.status='ON_SALE' AND c.enabled=1 AND u.status='ACTIVE' FOR SHARE")){s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new ApiException(404,40401,"商品不存在或已下架");}}}
            if(active!=null){try(PreparedStatement s=c.prepareStatement("INSERT INTO favorites(user_id,product_id,active) SELECT ?,id,? FROM products WHERE id=? ON DUPLICATE KEY UPDATE active=?")){s.setLong(1,user);s.setBoolean(2,active);s.setLong(3,id);s.setBoolean(4,active);s.executeUpdate();}}
            boolean result;try(PreparedStatement s=c.prepareStatement("SELECT active FROM favorites WHERE user_id=? AND product_id=?")){s.setLong(1,user);s.setLong(2,id);try(ResultSet r=s.executeQuery()){result=r.next()&&r.getBoolean(1);}}c.commit();return result;
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    private static ApiException bad(String message){return new ApiException(400,40001,message);}
}
