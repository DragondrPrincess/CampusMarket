package com.campusmarket;

import com.campusmarket.auth.PasswordHasher;
import com.campusmarket.dao.UserDao;
import java.sql.*;
import java.util.UUID;

/** Small, explicitly fictional demo dataset. No existing record is overwritten. */
public final class CatalogSeed {
    public static void main(String[] args){try{run();}catch(SQLException e){System.err.println("CATALOG SEED FAIL: state="+e.getSQLState()+"; code="+e.getErrorCode());System.exit(1);}catch(RuntimeException e){System.err.println("CATALOG SEED FAIL: "+e.getClass().getSimpleName());System.exit(1);}}
    private static void run()throws SQLException{
        UserDao dao=new UserDao();String name="p4_demo_seller";UserDao.User seller=dao.findByUsername(name);
        if(seller==null){dao.insertUser(name,PasswordHasher.hash(UUID.randomUUID().toString()),"商品演示卖家（虚构）");seller=dao.findByUsername(name);}
        if(!seller.nickname().equals("商品演示卖家（虚构）"))throw new IllegalStateException("Demo account collision");
        String[][] rows={
            {"books","高等数学教材（演示）","18.00"},{"books","大学英语学习词典（演示）","25.00"},
            {"digital","无线鼠标（演示）","35.00"},{"digital","机械键盘（演示）","89.00"},
            {"dorm","护眼台灯（演示）","45.00"},{"sports","羽毛球拍（演示）","60.00"},
            {"clothing","帆布双肩包（演示）","29.00"},{"other","桌面收纳架（演示）","12.00"},
            {"other","已下架样例（隐藏演示）","1.00"}
        };
        try(Connection connection=Database.open()){
            connection.setAutoCommit(false);
            try{
                for(String[] row:rows){try(PreparedStatement sql=connection.prepareStatement(
                    "INSERT INTO products(seller_id,category_id,title,description,price,condition_label,trade_location,status) "
                    +"SELECT ?,c.id,?,?,?,'九成新','图书馆门口',? FROM categories c WHERE c.code=? "
                    +"AND NOT EXISTS(SELECT 1 FROM products p WHERE p.seller_id=? AND p.title=?)")){
                    sql.setLong(1,seller.id());sql.setString(2,row[1]);sql.setString(3,"虚构课程演示商品，不代表真实在售物品。物品保存完好，供分类、搜索、分页和详情展示验证。");
                    sql.setBigDecimal(4,new java.math.BigDecimal(row[2]));sql.setString(5,row[1].startsWith("已下架")?"OFF_SHELF":"ON_SALE");
                    sql.setString(6,row[0]);sql.setLong(7,seller.id());sql.setString(8,row[1]);sql.executeUpdate();
                }}connection.commit();
            }catch(SQLException e){connection.rollback();throw e;}
        }
        System.out.println("CATALOG SEED PASS: eight public demo products and one hidden fixture; existing records retained; no credentials printed.");
    }
}
