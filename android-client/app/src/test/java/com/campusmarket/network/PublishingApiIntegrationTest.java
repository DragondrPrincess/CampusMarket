package com.campusmarket.network;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Actual Android Java transport and Gson against Tomcat/MySQL, without starting a device. */
public final class PublishingApiIntegrationTest {
    private static final String BASE="http://127.0.0.1:8080/campus-market/api/v1";
    @Test public void publishUploadManageAndFavorite()throws Exception {
        UserApi users=new UserApi(BASE);CatalogApi api=new CatalogApi(BASE);
        String username="p5_client_"+UUID.randomUUID().toString().replace("-","").substring(0,12),password=UUID.randomUUID()+"Z9";
        users.register(username,password,password,"客户端虚构验收用户");UserApi.Login login=users.login(username,password);String token=login.token;
        byte[] bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        CatalogApi.Image image=users.upload(token,bytes,"image/png");assertTrue(image.id>0);assertEquals(1,image.width);
        Map<String,Object> draft=new LinkedHashMap<>();draft.put("title","客户端虚构发布验收商品");draft.put("description","真实客户端网络代码验收，虚构记录");draft.put("categoryId",api.categories()[0].id);draft.put("price","8.50");draft.put("conditionLabel","八成新");draft.put("tradeLocation","虚构测试校区");draft.put("imageIds",Collections.singletonList(image.id));
        long id=0;boolean deleted=false;
        try{
            CatalogApi.Product product=api.save(token,0,draft);id=product.id;assertEquals(login.user.id,product.sellerId);assertEquals("ON_SALE",product.status);assertEquals(image.id,product.images[0].id);
            assertEquals(1,api.mine(token,1,false).total);assertEquals(id,api.own(token,id).id);assertEquals("8.50",api.detail(id).price);
            assertFalse(api.favorite(token,id,null));assertTrue(api.favorite(token,id,true));assertEquals(id,api.mine(token,1,true).items[0].id);assertFalse(api.favorite(token,id,false));
            draft.put("price","9.25");assertEquals("9.25",api.save(token,id,draft).price);api.status(token,id,"OFF_SHELF");assertEquals("OFF_SHELF",api.own(token,id).status);
            try{api.detail(id);fail("Off shelf must be hidden");}catch(UserApi.Failure failure){assertEquals(404,failure.status);}
            api.status(token,id,"ON_SALE");assertEquals(id,api.detail(id).id);api.delete(token,id);deleted=true;assertEquals(0,api.mine(token,1,false).total);
            users.logout(token);try{api.mine(token,1,false);fail("Revoked token must fail");}catch(UserApi.Failure failure){assertEquals(401,failure.status);}
        }finally{if(id>0&&!deleted)api.delete(token,id);}
    }
    @Test public void unauthenticatedManagementRejected()throws Exception {
        try{new CatalogApi(BASE).mine(null,1,false);fail("Login required");}catch(UserApi.Failure failure){assertEquals(401,failure.status);}
    }
}
