package com.campusmarket.dao;

import com.campusmarket.Database;
import com.campusmarket.auth.ApiException;
import java.sql.*;
import java.util.*;

public final class CatalogDao {
    private static final String FROM = " FROM products p JOIN categories c ON c.id=p.category_id JOIN users u ON u.id=p.seller_id ";
    private static final String VISIBLE = " p.status='ON_SALE' AND c.enabled=1 AND u.status='ACTIVE' ";
    private static final String COLUMNS = "p.id,p.seller_id,p.category_id,p.title,p.description,p.price,p.condition_label,p.trade_location,p.status,p.created_at,c.name AS category_name,c.code AS category_code,u.nickname AS seller_nickname,u.school AS seller_school,u.campus AS seller_campus";

    public List<Map<String,Object>> categories() throws SQLException {
        try(Connection connection=Database.open(); PreparedStatement sql=connection.prepareStatement("SELECT id,code,name FROM categories WHERE enabled=1 ORDER BY sort_order,id")) {
            sql.setQueryTimeout(5);
            try(ResultSet rows=sql.executeQuery()) {
                List<Map<String,Object>> result=new ArrayList<>();
                while(rows.next()) result.add(Map.of("id",rows.getLong(1),"code",rows.getString(2),"name",rows.getString(3)));
                return result;
            }
        }
    }
    public Map<String,Object> list(int page,int size,long category,String keyword,String sort) throws SQLException {
        String filter=VISIBLE+(category>0?" AND p.category_id=?":"")+(!keyword.isEmpty()?" AND (p.title LIKE ? ESCAPE '!' OR p.description LIKE ? ESCAPE '!')":"");
        String order=switch(sort){case "latest"->"p.created_at DESC,p.id DESC";case "price_asc"->"p.price ASC,p.id DESC";case "price_desc"->"p.price DESC,p.id DESC";default->throw new ApiException(400,40001,"不支持的排序方式");};
        try(Connection connection=Database.open()) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try {
                long total;
                try(PreparedStatement sql=connection.prepareStatement("SELECT COUNT(*)"+FROM+" WHERE "+filter)) {
                    bind(sql,category,keyword);try(ResultSet rows=sql.executeQuery()){rows.next();total=rows.getLong(1);}
                }
                List<Map<String,Object>> items=new ArrayList<>();
                try(PreparedStatement sql=connection.prepareStatement("SELECT "+COLUMNS+FROM+" WHERE "+filter+" ORDER BY "+order+" LIMIT ? OFFSET ?")) {
                    int index=bind(sql,category,keyword);sql.setInt(index++,size);sql.setInt(index,(page-1)*size);
                    try(ResultSet rows=sql.executeQuery()){while(rows.next())items.add(product(rows,false));}
                }
                for(Map<String,Object> item:items)attachImages(connection,item);
                connection.commit();return Map.of("items",items,"page",page,"pageSize",size,"total",total);
            }catch(SQLException|RuntimeException exception){connection.rollback();throw exception;}
        }
    }
    public Map<String,Object> detail(long id) throws SQLException {
        try(Connection connection=Database.open();PreparedStatement sql=connection.prepareStatement("SELECT "+COLUMNS+FROM+" WHERE "+VISIBLE+" AND p.id=?")) {
            sql.setQueryTimeout(5);sql.setLong(1,id);
            try(ResultSet rows=sql.executeQuery()) {
                if(!rows.next())throw new ApiException(404,40401,"商品不存在或已下架");
                Map<String,Object> value=product(rows,true);attachImages(connection,value);return value;
            }
        }
    }
    public Map<String,Object> ownerDetail(long user,long id)throws SQLException {
        try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("SELECT "+COLUMNS+FROM+" WHERE p.id=? AND p.seller_id=? AND p.status<>'DELETED'")){
            s.setLong(1,id);s.setLong(2,user);try(ResultSet r=s.executeQuery()){if(!r.next())throw new ApiException(404,40401,"未找到自己的商品");Map<String,Object> value=product(r,true);attachImages(c,value);return value;}
        }
    }
    public Map<String,Object> filtered(String filter,long user,int page,int size)throws SQLException {
        try(Connection c=Database.open()){
            c.setReadOnly(true);c.setAutoCommit(false);
            try{
                long total;try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*)"+FROM+" WHERE "+filter)){s.setLong(1,user);try(ResultSet r=s.executeQuery()){r.next();total=r.getLong(1);}}
                List<Map<String,Object>> items=new ArrayList<>();
                try(PreparedStatement s=c.prepareStatement("SELECT "+COLUMNS+FROM+" WHERE "+filter+" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?")){s.setLong(1,user);s.setInt(2,size);s.setInt(3,(page-1)*size);try(ResultSet r=s.executeQuery()){while(r.next())items.add(product(r,true));}}
                for(Map<String,Object> item:items)attachImages(c,item);c.commit();return Map.of("items",items,"total",total,"page",page,"pageSize",size);
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private static void attachImages(Connection c,Map<String,Object> product)throws SQLException {
        List<Map<String,Object>> images=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT id,width,height FROM product_images WHERE product_id=? AND active=1 ORDER BY position,id")){
            s.setLong(1,((Number)product.get("id")).longValue());try(ResultSet r=s.executeQuery()){while(r.next())images.add(Map.of("id",r.getLong(1),"url","/images/"+r.getLong(1),"width",r.getInt(2),"height",r.getInt(3)));}
        }
        product.put("images",images);
    }
    private static int bind(PreparedStatement sql,long category,String keyword)throws SQLException {
        sql.setQueryTimeout(5);int index=1;if(category>0)sql.setLong(index++,category);
        if(!keyword.isEmpty()){String escaped="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";sql.setString(index++,escaped);sql.setString(index++,escaped);}
        return index;
    }
    private static Map<String,Object> product(ResultSet row,boolean detail)throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",row.getLong("id"));value.put("sellerId",row.getLong("seller_id"));value.put("categoryId",row.getLong("category_id"));
        value.put("title",row.getString("title"));value.put("price",row.getBigDecimal("price").toPlainString());
        value.put("status",row.getString("status"));
        value.put("categoryName",row.getString("category_name"));value.put("categoryCode",row.getString("category_code"));
        value.put("conditionLabel",row.getString("condition_label"));value.put("tradeLocation",row.getString("trade_location"));
        value.put("sellerNickname",row.getString("seller_nickname"));value.put("createdAt",row.getString("created_at"));
        if(detail){value.put("description",row.getString("description"));value.put("sellerSchool",row.getString("seller_school"));value.put("sellerCampus",row.getString("seller_campus"));}
        return value;
    }
}
