package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.dao.CatalogDao;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet(urlPatterns={"/api/v1/categories","/api/v1/products","/api/v1/products/*"})
public final class CatalogServlet extends HttpServlet {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final CatalogDao dao=new CatalogDao();
    @Override protected void service(HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        try {
            if(!request.getMethod().equals("GET")){
                PublishingServlet.productWrite(request,response);return;
            }
            Object data;
            if(request.getServletPath().endsWith("categories"))data=dao.categories();
            else if(request.getPathInfo()!=null){String path=request.getPathInfo();if(!path.matches("/[1-9][0-9]{0,17}"))throw new ApiException(400,40001,"商品编号不正确");data=dao.detail(Long.parseLong(path.substring(1)));}
            else {
                int page=integer(request,"page",1,1,10000),size=integer(request,"pageSize",20,1,50);
                String category=request.getParameter("categoryId");long id=0;
                if(category!=null){if(!category.matches("[1-9][0-9]{0,17}"))throw new ApiException(400,40001,"分类编号不正确");id=Long.parseLong(category);}
                String keyword=request.getParameter("keyword");keyword=keyword==null?"":keyword.strip();
                if(keyword.codePointCount(0,keyword.length())>100||keyword.codePoints().anyMatch(Character::isISOControl))throw new ApiException(400,40001,"搜索词过长或包含无效字符");
                String sort=request.getParameter("sort");data=dao.list(page,size,id,keyword,sort==null?"latest":sort);
            }
            write(response,200,0,"ok",data);
        }catch(ApiException exception){write(response,exception.status,exception.code,exception.getMessage(),null);}
        catch(SQLException exception){getServletContext().log("Catalog SQL failure; state="+exception.getSQLState()+"; code="+exception.getErrorCode());write(response,503,50301,"商品服务暂不可用，请重试",null);}
    }
    private static int integer(HttpServletRequest request,String key,int fallback,int min,int max){
        String text=request.getParameter(key);if(text==null)return fallback;
        try{int value=Integer.parseInt(text);if(value>=min&&value<=max)return value;}catch(NumberFormatException ignored){}
        throw new ApiException(400,40001,"分页参数不正确");
    }
    private static void write(HttpServletResponse response,int status,int code,String message,Object data)throws IOException {
        response.setStatus(status);Map<String,Object> body=new LinkedHashMap<>();body.put("code",code);body.put("message",message);body.put("data",data);response.getWriter().write(JSON.toJson(body));
    }
}
