package com.campusmarket.network;

import java.io.IOException;
import java.net.URLEncoder;

public final class CatalogApi {
    public static final class Category { public long id; public String name,code; }
    public static final class Image { public long id; public String url; public int width,height; }
    public static final class Product {
        public long id,sellerId,categoryId;
        public String title,price,description,categoryName,categoryCode,conditionLabel,tradeLocation,createdAt;
        public String sellerNickname,sellerSchool,sellerCampus;
        public String status;
        public Image[] images;
    }
    public static final class Page { public Product[] items; public int page,pageSize; public long total; }
    private final UserApi transport;
    public CatalogApi(){transport=new UserApi();}
    CatalogApi(String baseUrl){transport=new UserApi(baseUrl);}
    public Category[] categories()throws IOException{return transport.request("GET","/categories",null,null,Category[].class);}
    public Page list(int page,long category,String keyword,String sort)throws IOException {
        String query="/products?page="+page+"&pageSize=6&sort="+sort+"&keyword="+URLEncoder.encode(keyword,"UTF-8");
        if(category>0)query+="&categoryId="+category;
        Page result=transport.request("GET",query,null,null,Page.class);
        if(result.items==null||result.page!=page||result.total<0)throw new IOException("Invalid catalog response");
        return result;
    }
    public Product detail(long id)throws IOException{return transport.request("GET","/products/"+id,null,null,Product.class);}
    public Page mine(String token,int page,boolean favorites)throws IOException{return transport.request("GET","/users/me/"+(favorites?"favorites":"products")+"?page="+page+"&pageSize=20",token,null,Page.class);}
    public Product own(String token,long id)throws IOException{return transport.request("GET","/users/me/products/"+id,token,null,Product.class);}
    public Product save(String token,long id,Object draft)throws IOException{return transport.request(id==0?"POST":"PUT","/products"+(id==0?"":"/"+id),token,draft,Product.class);}
    public void status(String token,long id,String status)throws IOException{transport.request("PUT","/products/"+id+"/status",token,java.util.Collections.singletonMap("status",status),Product.class);}
    public void delete(String token,long id)throws IOException{transport.request("DELETE","/products/"+id,token,null,Void.class);}
    public static final class Favorite { public boolean active; }
    public boolean favorite(String token,long id,Boolean active)throws IOException{return transport.request(active==null?"GET":active?"PUT":"DELETE","/favorites/"+id,token,null,Favorite.class).active;}
}
