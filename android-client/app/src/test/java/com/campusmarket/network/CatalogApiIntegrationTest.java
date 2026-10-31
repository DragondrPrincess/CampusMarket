package com.campusmarket.network;

import org.junit.Test;
import static org.junit.Assert.*;
import java.math.BigDecimal;

/** Runs the actual Android transport/Gson code on the host JVM against local Tomcat. */
public final class CatalogApiIntegrationTest {
    private final CatalogApi api=new CatalogApi("http://127.0.0.1:8080/campus-market/api/v1");
    @Test public void categoriesAndPagination()throws Exception{
        assertTrue(api.categories().length>=6);
        CatalogApi.Page first=api.list(1,0,"","latest"),second=api.list(2,0,"","latest");
        assertTrue(first.total>=8);assertEquals(6,first.items.length);assertEquals(first.total,second.total);
        for(CatalogApi.Product left:first.items)for(CatalogApi.Product right:second.items)assertNotEquals(left.id,right.id);
    }
    @Test public void searchCategoryAndDetails()throws Exception{
        CatalogApi.Page found=api.list(1,0,"高等数学","latest");assertTrue(found.items.length>0);
        CatalogApi.Product item=api.detail(found.items[0].id);assertTrue(item.description.contains("虚构"));assertNotNull(item.sellerNickname);
        for(CatalogApi.Product product:api.list(1,item.categoryId,"","latest").items)assertEquals(item.categoryId,product.categoryId);
    }
    @Test public void sortingAndEmptyState()throws Exception{
        CatalogApi.Product[] items=api.list(1,0,"","price_asc").items;
        for(int index=1;index<items.length;index++)assertTrue(new BigDecimal(items[index-1].price).compareTo(new BigDecimal(items[index].price))<=0);
        assertEquals(0,api.list(1,0,"no_such_item_2f33d18f","latest").items.length);
        assertEquals(0,api.list(1,0,"%","latest").items.length);
    }
    @Test public void errorResponsePreservesStatus()throws Exception{
        try{api.detail(999999999999999999L);fail("Missing item should fail");}catch(UserApi.Failure failure){assertEquals(404,failure.status);}
    }
}
