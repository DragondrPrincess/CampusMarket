package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.dao.PublishingDao;
import com.campusmarket.service.AuthService;
import com.google.gson.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

@WebServlet(urlPatterns={"/api/v1/users/me/products","/api/v1/users/me/products/*","/api/v1/users/me/favorites","/api/v1/favorites/*"})
public final class PublishingServlet extends HttpServlet {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    @Override protected void service(HttpServletRequest r,HttpServletResponse s)throws IOException {
        try {
            long user=new AuthService().requireUser(r.getHeader("Authorization")).id();
            PublishingDao dao=new PublishingDao();String route=r.getServletPath(),method=r.getMethod();Object data;
            if(route.endsWith("favorites")&&r.getPathInfo()!=null){long id=id(r.getPathInfo());Boolean active=switch(method){case "GET"->null;case "PUT"->true;case "DELETE"->false;default->throw method();};data=Map.of("active",dao.favorite(user,id,active));}
            else if(method.equals("GET")&&route.endsWith("products")&&r.getPathInfo()!=null)data=dao.ownDetail(user,id(r.getPathInfo()));
            else if(method.equals("GET"))data=dao.mine(user,page(r,"page",1,10000),page(r,"pageSize",20,50),route.endsWith("favorites"));
            else throw method();
            write(s,200,0,"ok",data);
        }catch(ApiException e){write(s,e.status,e.code,e.getMessage(),null);}
        catch(SQLException e){getServletContext().log("Publishing SQL state="+e.getSQLState()+"; code="+e.getErrorCode());write(s,503,50301,"商品服务暂不可用",null);}
    }
    static void productWrite(HttpServletRequest r,HttpServletResponse s)throws IOException,SQLException {
        r.setCharacterEncoding("UTF-8");
        try{
            if(r.getServletPath().endsWith("categories"))throw method();
            String method=r.getMethod(),path=r.getPathInfo();
            if(!Set.of("POST","PUT","DELETE").contains(method))throw method();
            long user=new AuthService().requireUser(r.getHeader("Authorization")).id();
            PublishingDao dao=new PublishingDao();Object data;
            if(method.equals("POST")&&path==null){long id=dao.save(user,0,PublishingDao.validate(UserServlet.body(r)));data=dao.ownDetail(user,id);write(s,201,0,"ok",data);return;}
            if(path!=null&&path.endsWith("/status")&&method.equals("PUT")){
                long id=id(path.substring(0,path.length()-7));String state=PublishingDao.text(UserServlet.body(r),"status",1,20);
                if(!Set.of("ON_SALE","OFF_SHELF").contains(state))throw new ApiException(400,40001,"请选择上架或下架");
                dao.status(user,id,state);data=dao.ownDetail(user,id);
            }else if(path!=null){long id=id(path);
                if(method.equals("PUT")){dao.save(user,id,PublishingDao.validate(UserServlet.body(r)));data=dao.ownDetail(user,id);}
                else if(method.equals("DELETE")){dao.status(user,id,"DELETED");data=null;}else throw method();
            }else throw method();
            write(s,200,0,"ok",data);
        }catch(JsonParseException|IllegalStateException e){throw new ApiException(400,40001,"JSON格式不正确");}
    }
    static long id(String path){if(path==null||!path.matches("/[1-9][0-9]{0,17}"))throw new ApiException(400,40001,"编号不正确");return Long.parseLong(path.substring(1));}
    private static int page(HttpServletRequest r,String key,int fallback,int max){String v=r.getParameter(key);if(v==null)return fallback;try{int n=Integer.parseInt(v);if(n>=1&&n<=max)return n;}catch(NumberFormatException ignored){}throw new ApiException(400,40001,"分页参数不正确");}
    private static ApiException method(){return new ApiException(405,40501,"不支持的请求方法");}
    static void write(HttpServletResponse s,int status,int code,String message,Object data)throws IOException {
        s.setCharacterEncoding("UTF-8");s.setContentType("application/json");s.setHeader("Cache-Control","no-store");s.setHeader("X-Content-Type-Options","nosniff");s.setStatus(status);
        Map<String,Object> body=new LinkedHashMap<>();body.put("code",code);body.put("message",message);body.put("data",data);s.getWriter().write(JSON.toJson(body));
    }
}
